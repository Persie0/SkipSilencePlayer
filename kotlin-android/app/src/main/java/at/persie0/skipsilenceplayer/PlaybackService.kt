@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package at.persie0.skipsilenceplayer

import android.app.PendingIntent
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
    val edgePaddingSeconds: Float
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

    fun reconfigureSilence(config: SilenceConfig) {
        service?.reconfigureSilence(config)
    }

    fun skippedSeconds(): Float = service?.skippedSeconds() ?: 0f
}

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var bundle: PlayerBundle? = null
    private var carriedSkippedSeconds = 0f
    private val chapterMap = linkedMapOf<Long, ChapterInfo>()

    override fun onCreate() {
        super.onCreate()
        PlaybackRuntime.attach(this)

        val prefs = getSharedPreferences("skip_silence_player", MODE_PRIVATE)
        val config = SilenceConfig(
            thresholdDb = prefs.getFloat("silence_threshold_db", -42f),
            minimumSilenceSeconds = prefs.getFloat("minimum_silence", 0.45f),
            edgePaddingSeconds = prefs.getFloat("edge_padding", 0.08f)
        )
        val initial = createPlayer(config)
        bundle = initial
        attachListeners(initial.player)

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

    fun skippedSeconds(): Float {
        val current = bundle ?: return carriedSkippedSeconds
        val sampleRate = current.player.audioFormat?.sampleRate?.takeIf { it > 0 } ?: 48_000
        return carriedSkippedSeconds +
            current.silenceProcessor.skippedFrames.toFloat() / sampleRate.toFloat()
    }

    fun reconfigureSilence(config: SilenceConfig) {
        val oldBundle = bundle ?: return
        val old = oldBundle.player

        carriedSkippedSeconds = skippedSeconds()

        val items = buildList {
            for (index in 0 until old.mediaItemCount) {
                add(old.getMediaItemAt(index))
            }
        }
        val currentIndex = old.currentMediaItemIndex.coerceAtLeast(0)
        val position = old.currentPosition.coerceAtLeast(0L)
        val playWhenReady = old.playWhenReady
        val playbackSpeed = old.playbackParameters.speed
        val repeatMode = old.repeatMode
        val shuffle = old.shuffleModeEnabled
        val trackSelectionParameters = old.trackSelectionParameters
        val volume = old.volume
        val skipSilence = old.skipSilenceEnabled

        val replacement = createPlayer(config)
        replacement.player.apply {
            repeatMode = repeatMode
            shuffleModeEnabled = shuffle
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
        old.release()
        PlaybackRuntime.playerReplaced()
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
