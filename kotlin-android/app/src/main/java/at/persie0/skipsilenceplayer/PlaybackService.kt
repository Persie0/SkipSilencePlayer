@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package at.persie0.skipsilenceplayer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class PlaybackBundle(
    val player: ExoPlayer,
    val silenceProcessor: SilenceSkippingAudioProcessor
)

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var bundle: PlaybackBundle? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this

        val preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val threshold = preferences.getFloat("silence_threshold_db", -42f)
        val minimum = preferences.getFloat("minimum_silence", 0.45f)
        val padding = preferences.getFloat("edge_padding", 0.08f)

        bundle = buildPlaybackBundle(this, threshold, minimum, padding)
        val player = requireNotNull(bundle).player

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (mediaSession?.player?.isPlaying != true) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        activeInstance = null
        mediaSession?.release()
        bundle?.player?.release()
        mediaSession = null
        bundle = null
        super.onDestroy()
    }

    fun reconfigureSilence(
        thresholdDb: Float,
        minimumSilenceSeconds: Float,
        edgePaddingSeconds: Float
    ) {
        val session = mediaSession ?: return
        val oldBundle = bundle ?: return
        val oldPlayer = oldBundle.player

        val items = (0 until oldPlayer.mediaItemCount).map(oldPlayer::getMediaItemAt)
        val index = oldPlayer.currentMediaItemIndex.coerceAtLeast(0)
        val position = oldPlayer.currentPosition.coerceAtLeast(0L)
        val playWhenReady = oldPlayer.playWhenReady
        val speed = oldPlayer.playbackParameters.speed
        val skipEnabled = oldPlayer.skipSilenceEnabled
        val repeatMode = oldPlayer.repeatMode
        val shuffle = oldPlayer.shuffleModeEnabled

        val newBundle = buildPlaybackBundle(
            this,
            thresholdDb,
            minimumSilenceSeconds,
            edgePaddingSeconds
        )
        val newPlayer = newBundle.player

        newPlayer.skipSilenceEnabled = skipEnabled
        newPlayer.setPlaybackSpeed(speed)
        newPlayer.repeatMode = repeatMode
        newPlayer.shuffleModeEnabled = shuffle

        if (items.isNotEmpty()) {
            newPlayer.setMediaItems(
                items,
                index.coerceIn(0, items.lastIndex),
                position
            )
            newPlayer.prepare()
        }
        newPlayer.playWhenReady = playWhenReady

        session.setPlayer(newPlayer)
        bundle = newBundle
        oldPlayer.release()
    }

    fun skippedSeconds(): Float {
        val current = bundle ?: return 0f
        val sampleRate = current.player.audioFormat?.sampleRate?.takeIf { it > 0 } ?: 48_000
        return current.silenceProcessor.skippedFrames.toFloat() / sampleRate.toFloat()
    }

    companion object {
        private const val PREFS = "skip_silence_player"

        @Volatile
        private var activeInstance: PlaybackService? = null

        fun reconfigure(
            thresholdDb: Float,
            minimumSilenceSeconds: Float,
            edgePaddingSeconds: Float
        ) {
            activeInstance?.reconfigureSilence(
                thresholdDb,
                minimumSilenceSeconds,
                edgePaddingSeconds
            )
        }

        fun setSkipSilence(enabled: Boolean) {
            activeInstance?.bundle?.player?.skipSilenceEnabled = enabled
        }

        fun currentSkippedSeconds(): Float =
            activeInstance?.skippedSeconds() ?: 0f
    }
}

@Suppress("DEPRECATION")
internal fun buildPlaybackBundle(
    context: Context,
    silenceThresholdDb: Float,
    minimumSilenceSeconds: Float,
    edgePaddingSeconds: Float
): PlaybackBundle {
    val minimumUs =
        (minimumSilenceSeconds.coerceIn(0.2f, 2f) * 1_000_000L).toLong()
    val requestedPaddingUs =
        (edgePaddingSeconds.coerceIn(0.02f, 0.20f) * 1_000_000L).toLong()
    val paddingUs =
        requestedPaddingUs.coerceAtMost((minimumUs / 2L).coerceAtLeast(1L))

    val silenceProcessor = SilenceSkippingAudioProcessor(
        minimumUs,
        paddingUs,
        dbToPcmThreshold(silenceThresholdDb)
    )

    val chain = DefaultAudioSink.DefaultAudioProcessorChain(
        emptyArray<AudioProcessor>(),
        silenceProcessor,
        SonicAudioProcessor()
    )

    val renderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean
        ): AudioSink {
            return DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(
                    enableAudioOutputPlaybackParams
                )
                .setAudioProcessorChain(chain)
                .build()
        }
    }

    val attributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    val player = ExoPlayer.Builder(context, renderersFactory)
        .setSeekBackIncrementMs(10_000L)
        .setSeekForwardIncrementMs(10_000L)
        .build()
        .apply {
            setAudioAttributes(attributes, true)
            setHandleAudioBecomingNoisy(true)
        }

    return PlaybackBundle(
        player = player,
        silenceProcessor = silenceProcessor
    )
}

private fun dbToPcmThreshold(db: Float): Short {
    val amplitude = 32767.0 * 10.0.pow(db.toDouble() / 20.0)
    return amplitude
        .roundToInt()
        .coerceIn(1, Short.MAX_VALUE.toInt())
        .toShort()
}
