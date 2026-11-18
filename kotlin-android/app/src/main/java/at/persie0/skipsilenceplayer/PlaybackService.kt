@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package at.persie0.skipsilenceplayer

import android.app.PendingIntent
import android.os.SystemClock
import android.net.Uri
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.roundToInt

data class ChapterInfo(
    val title: String,
    val startMs: Long,
    val endMs: Long?
)

data class SilenceConfig(
    val thresholdDb: Float,
    val minimumSilenceSeconds: Float,
    val edgePaddingSeconds: Float,
    val adaptiveEnabled: Boolean = true
)

private data class PlayerBundle(
    val player: ExoPlayer,
    val silenceProcessor: SilenceSkippingAudioProcessor
)

object PlaybackRuntime {
    var chapters by mutableStateOf<List<ChapterInfo>>(emptyList())
        private set

    var generation by mutableStateOf(0)
        private set

    var silenceRanges by mutableStateOf<List<SilenceRangeMs>>(emptyList())
        private set
    var analysisStatus by mutableStateOf("Open a local video to analyze silence")
        private set
    var effectiveThresholdDb by mutableStateOf(-42f)
        private set
    var isAnalyzing by mutableStateOf(false)
        private set

    private var service: PlaybackService? = null

    internal fun attach(service: PlaybackService) {
        this.service = service
    }

    internal fun detach(service: PlaybackService) {
        if (this.service === service) {
            this.service = null
        }
    }

    internal fun updateChapters(chapters: List<ChapterInfo>) {
        this.chapters = chapters.sortedBy { it.startMs }.distinctBy { it.startMs }
    }

    internal fun playerReplaced() {
        generation++
    }

    internal fun publishAnalysis(
        ranges: List<SilenceRangeMs>,
        status: String,
        thresholdDb: Float,
        analyzing: Boolean
    ) {
        silenceRanges = ranges
        analysisStatus = status
        effectiveThresholdDb = thresholdDb
        isAnalyzing = analyzing
    }

    fun reconfigureSilence(config: SilenceConfig) {
        service?.reconfigureSilence(config)
    }

    fun setSkipSilenceEnabled(enabled: Boolean) {
        service?.setSkipSilenceEnabled(enabled)
    }

    fun skippedSeconds(): Float = service?.skippedSeconds() ?: 0f
}

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var bundle: PlayerBundle? = null
    private var carriedSkippedSeconds = 0f
    private val chapterMap = linkedMapOf<Long, ChapterInfo>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var analysisJob: Job? = null
    private var analysisRevision = 0
    private var analyzedUri: Uri? = null
    private var analysisReady = false
    private var requestedSkipSilence = true
    private var timelineSkipSeconds = 0f
    private var lastAutomaticSeekAtMs = 0L
    private var lastAutomaticSeekTargetMs = -1L
    private var currentConfig = SilenceConfig(-42f, 0.45f, 0.08f)


    override fun onCreate() {
        super.onCreate()
        PlaybackRuntime.attach(this)

        val prefs = getSharedPreferences("skip_silence_player", MODE_PRIVATE)
        val config = SilenceConfig(
            thresholdDb = prefs.getFloat("silence_threshold_db", -42f),
            minimumSilenceSeconds = prefs.getFloat("minimum_silence", 0.45f),
            edgePaddingSeconds = prefs.getFloat("edge_padding", 0.08f),
            adaptiveEnabled = prefs.getBoolean("adaptive_silence", true)
        )
        currentConfig = config
        requestedSkipSilence = prefs.getBoolean("skip_silence", true)
        val initial = createPlayer(config)
        bundle = initial
        attachListeners(initial.player)
        syncNativeSilence()

        // Runs in the service, not the activity: background playback remains
        // synchronized to the already-analyzed video timeline.
        scope.launch {
            while (isActive) {
                maybeSkipSilence()
                delay(35L)
            }
        }

        val activityIntent = Intent(this, MainActivity::class.java)
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, initial.player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = bundle?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        analysisJob?.cancel()
        scope.cancel()
        val currentSession = mediaSession
        val currentBundle = bundle
        mediaSession = null
        bundle = null

        currentSession?.release()
        currentBundle?.player?.release()

        PlaybackRuntime.updateChapters(emptyList())
        PlaybackRuntime.detach(this)
        super.onDestroy()
    }

    fun setSkipSilenceEnabled(enabled: Boolean) {
        requestedSkipSilence = enabled
        syncNativeSilence()
    }

    private fun syncNativeSilence() {
        // Never combine native stream shortening and timestamp-based seeks:
        // doing both skips audio twice and desynchronizes the counters.
        bundle?.player?.skipSilenceEnabled = requestedSkipSilence && !analysisReady
    }

    fun skippedSeconds(): Float {
        val current = bundle ?: return timelineSkipSeconds
        if (analysisReady || !requestedSkipSilence) return timelineSkipSeconds
        val sampleRate = current.player.audioFormat?.sampleRate?.takeIf { it > 0 } ?: 48_000
        return timelineSkipSeconds + carriedSkippedSeconds +
            current.silenceProcessor.skippedFrames.toFloat() / sampleRate
    }

    private fun maybeSkipSilence() {
        if (!requestedSkipSilence || !analysisReady) return
        val player = bundle?.player ?: return
        if (!player.isPlaying || analyzedUri != player.currentMediaItem?.localConfiguration?.uri) {
            return
        }
        val position = player.currentPosition
        val range = AdaptiveSilenceDetector.containingRange(
            PlaybackRuntime.silenceRanges, position
        ) ?: return
        if (range.endMs - position < 65L) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastAutomaticSeekAtMs < 170L) return
        if (
            lastAutomaticSeekTargetMs == range.endMs &&
            now - lastAutomaticSeekAtMs < 600L
        ) return

        val target = if (player.duration > 0 && player.duration != C.TIME_UNSET) {
            range.endMs.coerceAtMost(player.duration)
        } else {
            range.endMs
        }
        if (target <= position + 65L) return
        lastAutomaticSeekAtMs = now
        lastAutomaticSeekTargetMs = range.endMs
        timelineSkipSeconds += (target - position) / 1000f
        player.seekTo(target)
    }

    private fun startAnalysis(player: ExoPlayer, resetForNewMedia: Boolean) {
        val uri = player.currentMediaItem?.localConfiguration?.uri
        analysisJob?.cancel()
        analysisRevision++
        val revision = analysisRevision

        if (resetForNewMedia) {
            timelineSkipSeconds = 0f
            carriedSkippedSeconds = 0f
            lastAutomaticSeekAtMs = 0L
            lastAutomaticSeekTargetMs = -1L
        }

        analyzedUri = uri
        analysisReady = false
        syncNativeSilence()
        PlaybackRuntime.publishAnalysis(
            emptyList(),
            if (uri == null) "No local media to analyze" else "Analyzing audio…",
            currentConfig.thresholdDb,
            uri != null
        )
        if (uri == null) return

        val config = currentConfig
        analysisJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    AndroidSilenceAnalyzer.analyzeDetailed(
                        context = this@PlaybackService,
                        uri = uri,
                        thresholdDb = config.thresholdDb,
                        minimumSilenceSeconds = config.minimumSilenceSeconds,
                        edgePaddingSeconds = config.edgePaddingSeconds,
                        adaptive = config.adaptiveEnabled
                    )
                }
                if (
                    revision != analysisRevision ||
                    bundle?.player?.currentMediaItem?.localConfiguration?.uri != uri
                ) return@launch

                // Preserve native fallback time already skipped before analysis.
                timelineSkipSeconds = skippedSeconds()
                carriedSkippedSeconds = 0f
                analysisReady = true
                syncNativeSilence()
                PlaybackRuntime.publishAnalysis(
                    result.ranges,
                    if (result.ranges.isEmpty()) "No sustained silence found" else
                        "${result.ranges.size} silent segments · " +
                            if (result.adaptive) "adaptive ${result.thresholdDb.toInt()} dB"
                            else "fixed ${result.thresholdDb.toInt()} dB",
                    result.thresholdDb,
                    false
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (revision != analysisRevision) return@launch
                analysisReady = false
                syncNativeSilence()
                PlaybackRuntime.publishAnalysis(
                    emptyList(),
                    "Analysis unavailable; using native fallback: " +
                        (error.localizedMessage ?: "unknown decoder error"),
                    config.thresholdDb,
                    false
                )
            }
        }
    }

    fun reconfigureSilence(config: SilenceConfig) {
        if (config == currentConfig) return
        currentConfig = config
        val oldBundle = bundle ?: return
        val old = oldBundle.player

        val wasAnalyzed = analysisReady
        val priorSkipped = skippedSeconds()
        timelineSkipSeconds = priorSkipped
        carriedSkippedSeconds = 0f
        analysisReady = false

        val items = buildList {
            for (index in 0 until old.mediaItemCount) {
                add(old.getMediaItemAt(index))
            }
        }
        val currentIndex = old.currentMediaItemIndex.coerceAtLeast(0)
        val position = old.currentPosition.coerceAtLeast(0L)
        val playWhenReady = old.playWhenReady
        val playbackSpeed = old.playbackParameters.speed
        val oldRepeatMode = old.repeatMode
        val oldShuffle = old.shuffleModeEnabled
        val trackSelectionParameters = old.trackSelectionParameters
        val volume = old.volume
        val skipSilence = requestedSkipSilence

        val replacement = createPlayer(config)
        replacement.player.apply {
            repeatMode = oldRepeatMode
            shuffleModeEnabled = oldShuffle
            this.trackSelectionParameters = trackSelectionParameters
            this.volume = volume
            skipSilenceEnabled = skipSilence
            setPlaybackSpeed(playbackSpeed)

            if (items.isNotEmpty()) {
                setMediaItems(
                    items,
                    currentIndex.coerceIn(0, items.lastIndex),
                    position
                )
                prepare()
            }
            this.playWhenReady = playWhenReady
        }

        attachListeners(replacement.player)
        mediaSession?.setPlayer(replacement.player)
        bundle = replacement
        syncNativeSilence()
        old.release()
        PlaybackRuntime.playerReplaced()
        startAnalysis(replacement.player, resetForNewMedia = false)
    }

    private fun createPlayer(config: SilenceConfig): PlayerBundle {
        val minimumUs =
            (config.minimumSilenceSeconds.coerceIn(0.2f, 2f) * 1_000_000L).toLong()
        val edgeUs =
            (config.edgePaddingSeconds.coerceIn(0.02f, 0.20f) * 1_000_000L).toLong()

        val retentionRatio =
            ((edgeUs * 2.0) / minimumUs.toDouble()).toFloat().coerceIn(0.02f, 0.8f)
        val maxSilenceToKeepUs = (edgeUs * 2L).coerceAtLeast(20_000L)

        val silenceProcessor = SilenceSkippingAudioProcessor(
            minimumUs,
            retentionRatio,
            maxSilenceToKeepUs,
            SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
            dbToPcmThreshold(config.thresholdDb)
        )

        val chain = DefaultAudioSink.DefaultAudioProcessorChain(
            emptyArray<AudioProcessor>(),
            silenceProcessor,
            SonicAudioProcessor()
        )
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessorChain(chain)
                    .build()
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .build()
        player.setHandleAudioBecomingNoisy(true)

        return PlayerBundle(player, silenceProcessor)
    }

    private fun attachListeners(player: ExoPlayer) {
        player.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int
                ) {
                    chapterMap.clear()
                    PlaybackRuntime.updateChapters(emptyList())
                    startAnalysis(
                        player,
                        resetForNewMedia = true
                    )
                }

                override fun onMetadata(metadata: Metadata) {
                    if (player.currentTimeline.isEmpty) return

                    val window = Timeline.Window()
                    player.currentTimeline.getWindow(
                        player.currentMediaItemIndex,
                        window
                    )
                    val windowOffsetMs = window.positionInFirstPeriodUs / 1000L

                    for (index in 0 until metadata.length()) {
                        val entry = metadata.get(index)
                        if (entry is Chapter && !entry.isHidden) {
                            val start =
                                (entry.startTimeMs - windowOffsetMs).coerceAtLeast(0L)
                            val end = entry.endTimeMs
                                .takeIf { it != C.TIME_UNSET }
                                ?.let { (it - windowOffsetMs).coerceAtLeast(start) }

                            chapterMap[start] = ChapterInfo(
                                title = entry.title?.value
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "Chapter ${chapterMap.size + 1}",
                                startMs = start,
                                endMs = end
                            )
                        }
                    }
                    PlaybackRuntime.updateChapters(chapterMap.values.toList())
                }
            }
        )
    }
}

private fun dbToPcmThreshold(db: Float): Short {
    val amplitude = 32767.0 * 10.0.pow(db.toDouble() / 20.0)
    return amplitude
        .roundToInt()
        .coerceIn(1, Short.MAX_VALUE.toInt())
        .toShort()
}
