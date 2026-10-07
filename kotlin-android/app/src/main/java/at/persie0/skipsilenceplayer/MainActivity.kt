@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package at.persie0.skipsilenceplayer

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    var pipMode by mutableStateOf(false)
        private set

    var incomingUris by mutableStateOf<List<Uri>>(emptyList())
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        incomingUris = extractIntentUris(intent)

        setContent {
            MaterialTheme {
                PlayerControllerHost(this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUris = extractIntentUris(intent)
    }

    fun consumeIncomingUris() {
        incomingUris = emptyList()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode = isInPictureInPictureMode
    }

    fun enterPlayerPip(width: Int, height: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val safeWidth = width.coerceAtLeast(16)
        val safeHeight = height.coerceAtLeast(9)
        enterPictureInPictureMode(
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(safeWidth, safeHeight))
                .build()
        )
    }

    fun setPlayerFullscreen(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(
            window,
            window.decorView
        )
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

private data class PlaylistEntry(
    val uri: Uri,
    val name: String,
    val suggestedSubtitleUri: Uri? = null
)

private data class RecentVideo(
    val uri: String,
    val name: String
)

private data class PlaybackBookmark(
    val positionMs: Long,
    val name: String
)

private data class PendingOpen(
    val entries: List<PlaylistEntry>,
    val startIndex: Int,
    val resumeMs: Long
)

private enum class ResumeMode(val label: String) {
    ALWAYS("Always resume"),
    ASK("Ask"),
    NEVER("Always restart")
}

private enum class VideoScaleMode(
    val label: String,
    val resizeMode: Int
) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    CROP("Crop", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    WIDTH("Fit width", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH)
}

private enum class SubtitleTone(
    val label: String,
    val argb: Int,
    val composeColor: Color
) {
    WHITE("White", android.graphics.Color.WHITE, Color.White),
    YELLOW("Yellow", android.graphics.Color.YELLOW, Color.Yellow),
    CYAN("Cyan", android.graphics.Color.CYAN, Color.Cyan)
}

private enum class SilencePreset(
    val label: String,
    val thresholdDb: Float,
    val minimumSilence: Float,
    val edgePadding: Float
) {
    CONSERVATIVE("Conservative", -50f, 0.8f, 0.15f),
    BALANCED("Balanced", -42f, 0.45f, 0.08f),
    AGGRESSIVE("Aggressive", -35f, 0.25f, 0.04f);

    companion object {
        fun matching(
            threshold: Float,
            minimum: Float,
            padding: Float
        ): SilencePreset? {
            return entries.firstOrNull {
                abs(it.thresholdDb - threshold) < 0.01f &&
                    abs(it.minimumSilence - minimum) < 0.01f &&
                    abs(it.edgePadding - padding) < 0.01f
            }
        }
    }
}

@Composable
private fun PlayerControllerHost(activity: MainActivity) {
    val context = activity.applicationContext
    val token = remember {
        SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
    }
    val controllerFuture = remember {
        MediaController.Builder(context, token).buildAsync()
    }
    var controller by remember {
        mutableStateOf<MediaController?>(null)
    }
    var connectionError by remember {
        mutableStateOf<String?>(null)
    }

    DisposableEffect(controllerFuture) {
        controllerFuture.addListener(
            {
                runCatching {
                    controllerFuture.get()
                }.onSuccess {
                    controller = it
                }.onFailure {
                    connectionError = it.message ?: "Could not start player service"
                }
            },
            ContextCompat.getMainExecutor(context)
        )

        onDispose {
            MediaController.releaseFuture(controllerFuture)
        }
    }

    val active = controller
    if (active == null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(
                connectionError ?: "Starting player…",
                color = Color.White
            )
        }
    } else {
        SkipSilencePlayerScreen(activity, active)
    }
}

@Composable
private fun SkipSilencePlayerScreen(
    activity: MainActivity,
    player: MediaController
) {
    val context = activity.applicationContext
    val preferences = remember {
        context.getSharedPreferences("skip_silence_player", Context.MODE_PRIVATE)
    }
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            .coerceAtLeast(1)
    }

    var brightness by remember {
        mutableFloatStateOf(
            activity.window.attributes.screenBrightness
                .takeIf { it >= 0f }
                ?: (
                    Settings.System.getInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        128
                    ) / 255f
                    )
        )
    }
    var volume by remember {
        mutableFloatStateOf(
            audioManager
                .getStreamVolume(AudioManager.STREAM_MUSIC)
                .toFloat() / maxVolume
        )
    }

    var skipSilence by remember {
        mutableStateOf(preferences.getBoolean("skip_silence", true))
    }
    var silenceThresholdDb by remember {
        mutableFloatStateOf(
            preferences.getFloat("silence_threshold_db", -42f)
        )
    }
    var minimumSilence by remember {
        mutableFloatStateOf(
            preferences.getFloat("minimum_silence", 0.45f)
        )
    }
    var edgePadding by remember {
        mutableFloatStateOf(
            preferences.getFloat("edge_padding", 0.08f)
        )
    }
    var playbackSpeed by remember {
        mutableFloatStateOf(
            preferences.getFloat("playback_speed", 1f)
        )
    }
    var doubleTapSeconds by remember {
        mutableIntStateOf(
            preferences.getInt("double_tap_seconds", 10)
        )
    }

    var subtitleOffsetMs by remember {
        mutableLongStateOf(
            preferences.getLong("subtitle_offset_ms", 0L)
        )
    }
    var subtitleFontSize by remember {
        mutableFloatStateOf(
            preferences.getFloat("subtitle_font_sp", 24f)
        )
    }
    var subtitleBottomFraction by remember {
        mutableFloatStateOf(
            preferences.getFloat("subtitle_bottom_fraction", 0.10f)
        )
    }
    var subtitleBackgroundOpacity by remember {
        mutableFloatStateOf(
            preferences.getFloat("subtitle_background_opacity", 0.65f)
        )
    }
    var subtitleTone by remember {
        mutableStateOf(
            runCatching {
                SubtitleTone.valueOf(
                    preferences.getString(
                        "subtitle_tone",
                        SubtitleTone.WHITE.name
                    )!!
                )
            }.getOrDefault(SubtitleTone.WHITE)
        )
    }

    var resumeMode by remember {
        mutableStateOf(
            runCatching {
                ResumeMode.valueOf(
                    preferences.getString(
                        "resume_mode",
                        ResumeMode.ALWAYS.name
                    )!!
                )
            }.getOrDefault(ResumeMode.ALWAYS)
        )
    }

    var scaleMode by remember {
        mutableStateOf(
            runCatching {
                VideoScaleMode.valueOf(
                    preferences.getString(
                        "scale_mode",
                        VideoScaleMode.FIT.name
                    )!!
                )
            }.getOrDefault(VideoScaleMode.FIT)
        )
    }
    var videoZoom by remember {
        mutableFloatStateOf(
            preferences.getFloat("video_zoom", 1f)
        )
    }
    var audioOnly by remember {
        mutableStateOf(
            preferences.getBoolean("audio_only", false)
        )
    }
    var gestureLocked by remember {
        mutableStateOf(false)
    }

    var currentMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentTracks by remember { mutableStateOf<Tracks?>(null) }
    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("No video selected") }
    var fileInfo by remember { mutableStateOf("") }
    var fileSizeText by remember { mutableStateOf("") }

    var controlsVisible by remember { mutableStateOf(true) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var gestureText by remember { mutableStateOf<String?>(null) }

    var recentVideos by remember {
        mutableStateOf(loadRecentVideos(preferences))
    }
    var playlistEntries by remember {
        mutableStateOf<List<PlaylistEntry>>(emptyList())
    }

    var selectedSubtitleUri by remember {
        mutableStateOf<Uri?>(null)
    }
    var subtitleName by remember {
        mutableStateOf<String?>(null)
    }
    var subtitleCues by remember {
        mutableStateOf<List<TimedSubtitle>>(emptyList())
    }
    var subtitleParseError by remember {
        mutableStateOf<String?>(null)
    }

    var playbackError by remember {
        mutableStateOf<String?>(null)
    }

    var preview by remember {
        mutableStateOf<SilencePreview?>(null)
    }
    var previewRunning by remember {
        mutableStateOf(false)
    }

    var sleepDeadlineMs by remember {
        mutableLongStateOf(0L)
    }
    var sleepRemainingMs by remember {
        mutableLongStateOf(0L)
    }

    var abStartMs by remember {
        mutableLongStateOf(-1L)
    }
    var abEndMs by remember {
        mutableLongStateOf(-1L)
    }

    var bookmarks by remember {
        mutableStateOf<List<PlaybackBookmark>>(emptyList())
    }

    var pendingOpen by remember {
        mutableStateOf<PendingOpen?>(null)
    }

    var skippedCarrySeconds by remember {
        mutableFloatStateOf(0f)
    }
    var lastServiceSkipped by remember {
        mutableFloatStateOf(0f)
    }
    var skippedSeconds by remember {
        mutableFloatStateOf(0f)
    }

    val latestBrightness = rememberUpdatedState(brightness)
    val latestVolume = rememberUpdatedState(volume)
    val latestDoubleTap = rememberUpdatedState(doubleTapSeconds)
    val latestGestureLocked = rememberUpdatedState(gestureLocked)

    fun setBrightness(value: Float) {
        val clamped = value.coerceIn(0.02f, 1f)
        brightness = clamped
        val attrs = activity.window.attributes
        attrs.screenBrightness = clamped
        activity.window.attributes = attrs
    }

    fun setVolume(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        volume = clamped
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (clamped * maxVolume)
                .roundToInt()
                .coerceIn(0, maxVolume),
            0
        )
    }

    fun saveCurrentVideoState() {
        val uri = currentUri ?: return
        val key = mediaKey(uri)
        preferences.edit()
            .putLong(
                "${key}_position",
                player.currentPosition.coerceAtLeast(0L)
            )
            .putFloat("${key}_speed", playbackSpeed)
            .putFloat("${key}_threshold", silenceThresholdDb)
            .putFloat("${key}_minimum", minimumSilence)
            .putFloat("${key}_padding", edgePadding)
            .putBoolean("${key}_skip", skipSilence)
            .putString(
                "${key}_subtitle",
                selectedSubtitleUri?.toString()
            )
            .apply()
    }

    fun loadSettingsFor(uri: Uri) {
        val key = mediaKey(uri)

        silenceThresholdDb = preferences.getFloat(
            "${key}_threshold",
            preferences.getFloat("silence_threshold_db", -42f)
        )
        minimumSilence = preferences.getFloat(
            "${key}_minimum",
            preferences.getFloat("minimum_silence", 0.45f)
        )
        edgePadding = preferences.getFloat(
            "${key}_padding",
            preferences.getFloat("edge_padding", 0.08f)
        )
        playbackSpeed = preferences.getFloat(
            "${key}_speed",
            preferences.getFloat("playback_speed", 1f)
        )
        skipSilence = preferences.getBoolean(
            "${key}_skip",
            preferences.getBoolean("skip_silence", true)
        )

        PlaybackService.reconfigure(
            silenceThresholdDb,
            minimumSilence,
            edgePadding
        )
        PlaybackService.setSkipSilence(skipSilence)
        player.setPlaybackSpeed(playbackSpeed)
    }

    fun commitOpen(
        entries: List<PlaylistEntry>,
        startIndex: Int,
        resumePositionMs: Long
    ) {
        if (entries.isEmpty()) return

        saveCurrentVideoState()

        val safeIndex = startIndex.coerceIn(0, entries.lastIndex)
        val chosen = entries[safeIndex]
        loadSettingsFor(chosen.uri)

        playlistEntries = entries
        val mediaItems = entries.map {
            buildMediaItem(it.uri, it.name)
        }

        player.setMediaItems(
            mediaItems,
            safeIndex,
            resumePositionMs.coerceAtLeast(0L)
        )
        player.prepare()
        PlaybackService.setSkipSilence(skipSilence)
        player.setPlaybackSpeed(playbackSpeed)
        player.play()

        currentUri = chosen.uri
        fileName = chosen.name
        controlsVisible = true
        playbackError = null

        val updated = listOf(
            RecentVideo(chosen.uri.toString(), chosen.name)
        ) + recentVideos.filterNot {
            it.uri == chosen.uri.toString()
        }
        recentVideos = updated.take(12)
        saveRecentVideos(preferences, recentVideos)
    }

    fun requestOpen(
        entries: List<PlaylistEntry>,
        startIndex: Int = 0
    ) {
        if (entries.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, entries.lastIndex)
        val uri = entries[safeIndex].uri
        val resume = preferences.getLong(
            "${mediaKey(uri)}_position",
            0L
        )

        when (resumeMode) {
            ResumeMode.ALWAYS -> {
                commitOpen(entries, safeIndex, resume)
            }

            ResumeMode.NEVER -> {
                commitOpen(entries, safeIndex, 0L)
            }

            ResumeMode.ASK -> {
                if (resume > 5_000L) {
                    pendingOpen = PendingOpen(
                        entries,
                        safeIndex,
                        resume
                    )
                } else {
                    commitOpen(entries, safeIndex, 0L)
                }
            }
        }
    }

    fun applySilenceSettings(
        threshold: Float = silenceThresholdDb,
        minimum: Float = minimumSilence,
        padding: Float = edgePadding
    ) {
        skippedCarrySeconds += lastServiceSkipped
        lastServiceSkipped = 0f

        silenceThresholdDb =
            threshold.coerceIn(-60f, -20f)
        minimumSilence =
            minimum.coerceIn(0.2f, 2f)
        edgePadding =
            padding.coerceIn(0.02f, 0.20f)

        preferences.edit()
            .putFloat(
                "silence_threshold_db",
                silenceThresholdDb
            )
            .putFloat(
                "minimum_silence",
                minimumSilence
            )
            .putFloat(
                "edge_padding",
                edgePadding
            )
            .apply()

        PlaybackService.reconfigure(
            silenceThresholdDb,
            minimumSilence,
            edgePadding
        )
        saveCurrentVideoState()
    }

    fun seekBy(deltaMs: Long) {
        if (player.mediaItemCount == 0) return
        val end =
            if (durationMs > 0L) durationMs else Long.MAX_VALUE
        player.seekTo(
            (player.currentPosition + deltaMs)
                .coerceIn(0L, end)
        )
        gestureText =
            if (deltaMs < 0) {
                "−${abs(deltaMs) / 1000} s"
            } else {
                "+${deltaMs / 1000} s"
            }
        controlsVisible = true
    }

    DisposableEffect(Unit) {
        onDispose {
            saveCurrentVideoState()
            activity.setPlayerFullscreen(false)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = readablePlaybackError(error)
                controlsVisible = true
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int
            ) {
                playbackError = null
                val uriString = mediaItem?.mediaId.orEmpty()
                val uri = uriString
                    .takeIf { it.isNotBlank() }
                    ?.let(Uri::parse)

                if (uri != null) {
                    currentUri = uri
                    fileName =
                        mediaItem?.mediaMetadata?.title?.toString()
                            ?: queryDisplayName(context, uri)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
        }
    }

    LaunchedEffect(skipSilence) {
        PlaybackService.setSkipSilence(skipSilence)
        preferences.edit()
            .putBoolean("skip_silence", skipSilence)
            .apply()
    }

    LaunchedEffect(playbackSpeed) {
        player.setPlaybackSpeed(
            playbackSpeed.coerceIn(0.5f, 3f)
        )
    }

    LaunchedEffect(activity.incomingUris, player) {
        val incoming = activity.incomingUris
        if (incoming.isNotEmpty()) {
            incoming.forEach {
                takePersistableReadPermission(activity, it)
            }
            val entries = incoming.map {
                PlaylistEntry(
                    uri = it,
                    name = queryDisplayName(context, it)
                )
            }
            requestOpen(entries)
            activity.consumeIncomingUris()
        }
    }

    LaunchedEffect(currentUri) {
        val uri = currentUri ?: return@LaunchedEffect

        fileSizeText = queryFileSize(context, uri)
        bookmarks = loadBookmarks(
            preferences,
            mediaKey(uri)
        )

        val matchingEntry = playlistEntries
            .firstOrNull { it.uri == uri }

        val savedSubtitle = preferences
            .getString(
                "${mediaKey(uri)}_subtitle",
                null
            )
            ?.let(Uri::parse)

        val subtitleUri =
            savedSubtitle ?: matchingEntry?.suggestedSubtitleUri

        selectedSubtitleUri = subtitleUri
        subtitleName =
            subtitleUri?.let {
                queryDisplayName(context, it)
            }

        subtitleCues = emptyList()
        subtitleParseError = null

        if (subtitleUri != null) {
            runCatching {
                ExternalSubtitleParser.parse(
                    context,
                    subtitleUri
                )
            }.onSuccess {
                subtitleCues = it
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(
                            C.TRACK_TYPE_TEXT,
                            true
                        )
                        .build()
            }.onFailure {
                subtitleParseError =
                    it.message ?: "Subtitle parse failed"
            }
        }

        preview = null
        previewRunning = true
        preview = AndroidSilencePreviewAnalyzer.analyze(
            context,
            uri,
            silenceThresholdDb,
            minimumSilence,
            edgePadding
        )
        previewRunning = false
    }

    LaunchedEffect(
        currentUri,
        silenceThresholdDb,
        minimumSilence,
        edgePadding
    ) {
        val uri = currentUri ?: return@LaunchedEffect
        previewRunning = true
        preview = AndroidSilencePreviewAnalyzer.analyze(
            context,
            uri,
            silenceThresholdDb,
            minimumSilence,
            edgePadding
        )
        previewRunning = false
    }

    LaunchedEffect(player) {
        var tick = 0
        var lastUri: Uri? = null

        while (isActive) {
            currentMs =
                player.currentPosition.coerceAtLeast(0L)
            val duration = player.duration
            durationMs =
                if (
                    duration != C.TIME_UNSET &&
                    duration > 0
                ) duration else 0L
            isPlaying = player.isPlaying
            currentTracks = player.currentTracks

            val mediaItem = player.currentMediaItem
            val uri = mediaItem?.mediaId
                ?.takeIf { it.isNotBlank() }
                ?.let(Uri::parse)

            if (uri != null && uri != lastUri) {
                lastUri = uri
                currentUri = uri
                fileName =
                    mediaItem.mediaMetadata.title
                        ?.toString()
                        ?: queryDisplayName(context, uri)
                fileSizeText =
                    queryFileSize(context, uri)
            }

            val serviceSkipped =
                PlaybackService.currentSkippedSeconds()
            if (
                serviceSkipped + 0.02f <
                lastServiceSkipped
            ) {
                skippedCarrySeconds +=
                    lastServiceSkipped
            }
            lastServiceSkipped = serviceSkipped
            skippedSeconds =
                skippedCarrySeconds + serviceSkipped

            val videoSize = player.videoSize
            val resolution =
                if (
                    videoSize.width > 0 &&
                    videoSize.height > 0
                ) {
                    "${videoSize.width}×${videoSize.height}"
                } else {
                    ""
                }

            fileInfo = listOf(
                fileSizeText,
                resolution,
                if (durationMs > 0L) {
                    formatTime(durationMs)
                } else {
                    ""
                },
                if (player.mediaItemCount > 1) {
                    "${player.currentMediaItemIndex + 1}/${player.mediaItemCount}"
                } else {
                    ""
                }
            )
                .filter { it.isNotBlank() }
                .joinToString(" · ")

            if (
                abStartMs >= 0L &&
                abEndMs > abStartMs &&
                currentMs >= abEndMs
            ) {
                player.seekTo(abStartMs)
            }

            if (sleepDeadlineMs > 0L) {
                sleepRemainingMs =
                    (sleepDeadlineMs -
                        System.currentTimeMillis())
                        .coerceAtLeast(0L)

                if (sleepRemainingMs <= 0L) {
                    player.pause()
                    sleepDeadlineMs = 0L
                    gestureText = "Sleep timer finished"
                }
            }

            tick++
            if (tick % 5 == 0) {
                saveCurrentVideoState()
            }

            delay(200)
        }
    }

    LaunchedEffect(gestureText) {
        if (gestureText != null) {
            delay(800)
            gestureText = null
        }
    }

    LaunchedEffect(
        isPlaying,
        controlsVisible,
        isFullscreen,
        activity.pipMode
    ) {
        if (
            isPlaying &&
            controlsVisible &&
            !activity.pipMode &&
            !advancedExpanded
        ) {
            delay(
                if (isFullscreen) 2_500
                else 4_000
            )
            controlsVisible = false
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            takePersistableReadPermission(activity, uri)
            requestOpen(
                listOf(
                    PlaylistEntry(
                        uri,
                        queryDisplayName(context, uri),
                        findSiblingSubtitle(context, uri)
                    )
                )
            )
        }
    }

    val playlistPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isNotEmpty()) {
                uris.forEach {
                    takePersistableReadPermission(
                        activity,
                        it
                    )
                }
                requestOpen(
                    uris.map {
                        PlaylistEntry(
                            it,
                            queryDisplayName(context, it)
                        )
                    }
                )
            }
        }

    val folderPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                takePersistableReadPermission(
                    activity,
                    uri
                )
                requestOpen(
                    scanVideoFolder(
                        context,
                        uri
                    )
                )
            }
        }

    val subtitlePicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (
                uri != null &&
                currentUri != null
            ) {
                takePersistableReadPermission(
                    activity,
                    uri
                )
                selectedSubtitleUri = uri
                subtitleName =
                    queryDisplayName(context, uri)
                preferences.edit()
                    .putString(
                        "${mediaKey(currentUri!!)}_subtitle",
                        uri.toString()
                    )
                    .apply()

                subtitleParseError = null
                subtitleCues = emptyList()

                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(
                            C.TRACK_TYPE_TEXT,
                            true
                        )
                        .build()
            }
        }

    LaunchedEffect(selectedSubtitleUri) {
        val uri =
            selectedSubtitleUri ?: return@LaunchedEffect
        runCatching {
            ExternalSubtitleParser.parse(
                context,
                uri
            )
        }.onSuccess {
            subtitleCues = it
            subtitleParseError = null
        }.onFailure {
            subtitleCues = emptyList()
            subtitleParseError =
                it.message ?: "Subtitle parse failed"
        }
    }

    pendingOpen?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                pendingOpen = null
            },
            title = {
                Text("Resume playback?")
            },
            text = {
                Text(
                    "Continue from ${formatTime(pending.resumeMs)}?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        commitOpen(
                            pending.entries,
                            pending.startIndex,
                            pending.resumeMs
                        )
                        pendingOpen = null
                    }
                ) {
                    Text("Resume")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        commitOpen(
                            pending.entries,
                            pending.startIndex,
                            0L
                        )
                        pendingOpen = null
                    }
                ) {
                    Text("Restart")
                }
            }
        )
    }

    val externalSubtitleText =
        ExternalSubtitleParser.cueAt(
            subtitleCues,
            currentMs,
            subtitleOffsetMs
        )

    val estimatedWatchMs = preview?.let {
        (
            (
                it.durationMs -
                    it.skippableMs
                ).coerceAtLeast(0L) /
                playbackSpeed.coerceAtLeast(0.01f)
            ).roundToLong()
    }
    val estimatedSavedMs =
        preview?.let {
            (
                it.durationMs -
                    (estimatedWatchMs ?: it.durationMs)
                ).coerceAtLeast(0L)
        }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
    ) {
        if (!audioOnly) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        setShowBuffering(
                            PlayerView
                                .SHOW_BUFFERING_WHEN_PLAYING
                        )
                        this.player = player
                    }
                },
                update = { view ->
                    view.player = player
                    view.resizeMode =
                        scaleMode.resizeMode

                    val subtitleView =
                        view.subtitleView
                    subtitleView?.setFractionalTextSize(
                        (
                            subtitleFontSize /
                                400f
                            ).coerceIn(
                                0.035f,
                                0.11f
                            )
                    )
                    subtitleView
                        ?.setBottomPaddingFraction(
                            subtitleBottomFraction
                        )
                    subtitleView?.setStyle(
                        CaptionStyleCompat(
                            subtitleTone.argb,
                            android.graphics.Color.argb(
                                (
                                    subtitleBackgroundOpacity *
                                        255
                                    ).roundToInt(),
                                0,
                                0,
                                0
                            ),
                            android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_NONE,
                            android.graphics.Color.TRANSPARENT,
                            Typeface.DEFAULT
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = videoZoom,
                        scaleY = videoZoom
                    )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(
                    player,
                    doubleTapSeconds,
                    gestureLocked
                ) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible =
                                !controlsVisible
                        },
                        onDoubleTap = { offset ->
                            if (
                                latestGestureLocked.value
                            ) {
                                controlsVisible = true
                                return@detectTapGestures
                            }

                            val delta =
                                latestDoubleTap.value *
                                    1000L
                            if (
                                offset.x <
                                size.width / 2f
                            ) {
                                seekBy(-delta)
                            } else {
                                seekBy(delta)
                            }
                        }
                    )
                }
                .pointerInput(
                    player,
                    gestureLocked
                ) {
                    var mode = 0
                    var startValue = 0f
                    var accumulatedX = 0f
                    var accumulatedY = 0f
                    var seekStart = 0L
                    var previewSeekDelta = 0L
                    var leftSide = true

                    detectDragGestures(
                        onDragStart = { offset ->
                            if (
                                latestGestureLocked.value
                            ) {
                                return@detectDragGestures
                            }

                            mode = 0
                            accumulatedX = 0f
                            accumulatedY = 0f
                            previewSeekDelta = 0L
                            seekStart =
                                player.currentPosition
                            leftSide =
                                offset.x <
                                    size.width / 2f
                            startValue =
                                if (leftSide) {
                                    latestBrightness.value
                                } else {
                                    latestVolume.value
                                }
                            controlsVisible = true
                        },
                        onDrag = { change, amount ->
                            if (
                                latestGestureLocked.value
                            ) {
                                return@detectDragGestures
                            }

                            change.consume()
                            accumulatedX += amount.x
                            accumulatedY += amount.y

                            if (mode == 0) {
                                if (
                                    abs(accumulatedX) >
                                    18f ||
                                    abs(accumulatedY) >
                                    18f
                                ) {
                                    mode =
                                        if (
                                            abs(accumulatedX) >
                                            abs(accumulatedY)
                                        ) 1 else 2
                                }
                            }

                            if (mode == 1) {
                                previewSeekDelta =
                                    (
                                        accumulatedX /
                                            max(
                                                size.width
                                                    .toFloat(),
                                                1f
                                            ) *
                                            120_000f
                                        )
                                        .roundToLong()
                                        .coerceIn(
                                            -120_000L,
                                            120_000L
                                        )

                                gestureText =
                                    "Seek " +
                                        if (
                                            previewSeekDelta <
                                            0
                                        ) {
                                            "−${abs(previewSeekDelta) / 1000} s"
                                        } else {
                                            "+${previewSeekDelta / 1000} s"
                                        }
                            } else if (mode == 2) {
                                val height =
                                    max(
                                        size.height
                                            .toFloat(),
                                        1f
                                    )
                                val next =
                                    (
                                        startValue -
                                            accumulatedY /
                                            height
                                        ).coerceIn(
                                            0f,
                                            1f
                                        )

                                if (leftSide) {
                                    setBrightness(next)
                                    gestureText =
                                        "Brightness " +
                                            (
                                                next *
                                                    100
                                                ).roundToInt() +
                                            "%"
                                } else {
                                    setVolume(next)
                                    gestureText =
                                        "Volume " +
                                            (
                                                next *
                                                    100
                                                ).roundToInt() +
                                            "%"
                                }
                            }
                        },
                        onDragEnd = {
                            if (
                                mode == 1 &&
                                !latestGestureLocked.value
                            ) {
                                val end =
                                    if (
                                        durationMs > 0L
                                    ) durationMs
                                    else Long.MAX_VALUE

                                player.seekTo(
                                    (
                                        seekStart +
                                            previewSeekDelta
                                        ).coerceIn(
                                            0L,
                                            end
                                        )
                                )
                            }
                        }
                    )
                }
        )

        if (
            player.mediaItemCount == 0 &&
            !activity.pipMode
        ) {
            Column(
                modifier =
                    Modifier.align(
                        Alignment.Center
                    ),
                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        videoPicker.launch(
                            arrayOf(
                                "video/*",
                                "video/x-matroska",
                                "video/webm",
                                "application/octet-stream"
                            )
                        )
                    }
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Open video")
                }

                Spacer(Modifier.height(8.dp))

                Row {
                    OutlinedButton(
                        onClick = {
                            playlistPicker.launch(
                                arrayOf("video/*")
                            )
                        }
                    ) {
                        Text("Open playlist")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            folderPicker.launch(null)
                        }
                    ) {
                        Text("Open folder")
                    }
                }
            }
        }

        if (audioOnly && !activity.pipMode) {
            Text(
                "Audio-only mode",
                color = Color.White.copy(alpha = 0.65f),
                modifier =
                    Modifier.align(
                        Alignment.Center
                    )
            )
        }

        if (
            externalSubtitleText.isNotBlank() &&
            !audioOnly
        ) {
            Text(
                text = externalSubtitleText,
                color = subtitleTone.composeColor,
                fontSize = subtitleFontSize.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom =
                            (
                                40 +
                                    subtitleBottomFraction *
                                    520
                                )
                                .roundToInt()
                                .dp,
                        start = 24.dp,
                        end = 24.dp
                    )
                    .background(
                        Color.Black.copy(
                            alpha =
                                subtitleBackgroundOpacity
                        ),
                        MaterialTheme.shapes.small
                    )
                    .padding(
                        horizontal = 10.dp,
                        vertical = 5.dp
                    )
            )
        }

        gestureText?.let { message ->
            if (!activity.pipMode) {
                Text(
                    text = message,
                    color = Color.White,
                    fontWeight =
                        FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(
                            Color.Black.copy(
                                alpha = 0.72f
                            ),
                            MaterialTheme.shapes.medium
                        )
                        .padding(
                            horizontal = 18.dp,
                            vertical = 12.dp
                        )
                )
            }
        }

        playbackError?.let { error ->
            if (!activity.pipMode) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(
                            Color.Black.copy(
                                alpha = 0.88f
                            ),
                            MaterialTheme.shapes.medium
                        )
                        .padding(18.dp),
                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {
                    Text(
                        "Playback error",
                        color = Color.White,
                        fontWeight =
                            FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        error,
                        color =
                            Color.White.copy(
                                alpha = 0.8f
                            )
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            playbackError = null
                            player.prepare()
                            player.play()
                        }
                    ) {
                        Text("Retry")
                    }
                }
            }
        }

        if (
            controlsVisible &&
            !activity.pipMode
        ) {
            Column(
                modifier = Modifier
                    .align(
                        Alignment.BottomCenter
                    )
                    .fillMaxWidth()
                    .heightIn(
                        max =
                            if (isFullscreen) {
                                390.dp
                            } else {
                                590.dp
                            }
                    )
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(
                                    alpha = 0.97f
                                )
                            )
                        )
                    )
                    .verticalScroll(
                        rememberScrollState()
                    )
                    .padding(
                        horizontal = 14.dp,
                        vertical = 8.dp
                    )
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            videoPicker.launch(
                                arrayOf(
                                    "video/*",
                                    "video/x-matroska",
                                    "video/webm",
                                    "application/octet-stream"
                                )
                            )
                        }
                    ) {
                        Text("Open")
                    }

                    Spacer(Modifier.width(6.dp))

                    OpenMenu(
                        onPlaylist = {
                            playlistPicker.launch(
                                arrayOf("video/*")
                            )
                        },
                        onFolder = {
                            folderPicker.launch(null)
                        }
                    )

                    Spacer(Modifier.width(6.dp))

                    RecentMenu(
                        videos = recentVideos,
                        onOpen = { recent ->
                            requestOpen(
                                listOf(
                                    PlaylistEntry(
                                        Uri.parse(
                                            recent.uri
                                        ),
                                        recent.name
                                    )
                                )
                            )
                        },
                        onClear = {
                            recentVideos =
                                emptyList()
                            preferences.edit()
                                .remove(
                                    "recent_videos"
                                )
                                .apply()
                        }
                    )

                    Spacer(
                        Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = {
                            gestureLocked =
                                !gestureLocked
                            controlsVisible = true
                        }
                    ) {
                        Icon(
                            if (gestureLocked) {
                                Icons.Default.Lock
                            } else {
                                Icons.Default.LockOpen
                            },
                            contentDescription =
                                "Gesture lock",
                            tint = Color.White
                        )
                    }

                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.O &&
                        player.mediaItemCount > 0 &&
                        !audioOnly
                    ) {
                        TextButton(
                            onClick = {
                                val size =
                                    player.videoSize
                                activity.enterPlayerPip(
                                    size.width,
                                    size.height
                                )
                            }
                        ) {
                            Text("PiP")
                        }
                    }

                    TextButton(
                        onClick = {
                            isFullscreen =
                                !isFullscreen
                            activity
                                .setPlayerFullscreen(
                                    isFullscreen
                                )
                            controlsVisible = true
                        }
                    ) {
                        Text(
                            if (isFullscreen) {
                                "Exit full"
                            } else {
                                "Full"
                            }
                        )
                    }
                }

                Text(
                    fileName,
                    color =
                        Color.White.copy(
                            alpha = 0.9f
                        ),
                    style =
                        MaterialTheme.typography
                            .bodySmall,
                    maxLines = 1
                )

                if (fileInfo.isNotBlank()) {
                    Text(
                        fileInfo,
                        color =
                            Color.White.copy(
                                alpha = 0.58f
                            ),
                        style =
                            MaterialTheme.typography
                                .labelSmall
                    )
                }

                if (previewRunning) {
                    Text(
                        "Analyzing silence preview…",
                        color =
                            Color.White.copy(
                                alpha = 0.58f
                            ),
                        style =
                            MaterialTheme.typography
                                .labelSmall
                    )
                } else if (
                    preview != null &&
                    estimatedWatchMs != null
                ) {
                    Text(
                        "Detected silence ${formatTime(preview!!.skippableMs)} · estimated watch ${formatTime(estimatedWatchMs)}" +
                            (
                                estimatedSavedMs
                                    ?.takeIf {
                                        it > 0L
                                    }
                                    ?.let {
                                        " · save ${formatTime(it)}"
                                    }
                                    ?: ""
                                ),
                        color =
                            Color.White.copy(
                                alpha = 0.72f
                            ),
                        style =
                            MaterialTheme.typography
                                .labelSmall
                    )
                }

                subtitleParseError?.let {
                    Text(
                        it,
                        color = Color.Yellow,
                        style =
                            MaterialTheme.typography
                                .labelSmall
                    )
                }

                Slider(
                    value =
                        currentMs
                            .coerceAtMost(
                                max(
                                    durationMs,
                                    0L
                                )
                            )
                            .toFloat(),
                    onValueChange = { value ->
                        if (durationMs > 0L) {
                            player.seekTo(
                                value.toLong()
                            )
                            controlsVisible = true
                        }
                    },
                    valueRange =
                        0f..max(
                            durationMs.toFloat(),
                            1f
                        ),
                    enabled =
                        durationMs > 0L
                )

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.SpaceBetween
                ) {
                    Text(
                        formatTime(currentMs),
                        color = Color.White,
                        style =
                            MaterialTheme.typography
                                .labelMedium
                    )
                    Text(
                        "Skipped ${formatTime((skippedSeconds * 1000f).toLong())}",
                        color =
                            Color.White.copy(
                                alpha = 0.72f
                            ),
                        style =
                            MaterialTheme.typography
                                .labelMedium
                    )
                    Text(
                        if (durationMs > 0L) {
                            "−" +
                                formatTime(
                                    (
                                        durationMs -
                                            currentMs
                                        ).coerceAtLeast(
                                            0L
                                        )
                                )
                        } else {
                            "--:--"
                        },
                        color = Color.White,
                        style =
                            MaterialTheme.typography
                                .labelMedium
                    )
                }

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.Center,
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            player
                                .seekToPreviousMediaItem()
                        },
                        enabled =
                            player
                                .hasPreviousMediaItem()
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription =
                                "Previous video",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            seekBy(
                                -doubleTapSeconds *
                                    1000L
                            )
                        },
                        enabled =
                            player.mediaItemCount > 0
                    ) {
                        Icon(
                            Icons.Default.FastRewind,
                            contentDescription =
                                "Back",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            if (player.isPlaying) {
                                player.pause()
                            } else {
                                player.play()
                            }
                            controlsVisible = true
                        },
                        enabled =
                            player.mediaItemCount > 0
                    ) {
                        Icon(
                            if (isPlaying) {
                                Icons.Default.Pause
                            } else {
                                Icons.Default.PlayArrow
                            },
                            contentDescription =
                                if (isPlaying) {
                                    "Pause"
                                } else {
                                    "Play"
                                },
                            tint = Color.White,
                            modifier =
                                Modifier.size(36.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            seekBy(
                                doubleTapSeconds *
                                    1000L
                            )
                        },
                        enabled =
                            player.mediaItemCount > 0
                    ) {
                        Icon(
                            Icons.Default.FastForward,
                            contentDescription =
                                "Forward",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            player
                                .seekToNextMediaItem()
                        },
                        enabled =
                            player.hasNextMediaItem()
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription =
                                "Next video",
                            tint = Color.White
                        )
                    }
                }

                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Text(
                        "Speed ${String.format("%.2f", playbackSpeed)}×",
                        color = Color.White,
                        style =
                            MaterialTheme.typography
                                .labelMedium,
                        modifier =
                            Modifier.width(105.dp)
                    )
                    Slider(
                        value = playbackSpeed,
                        onValueChange = {
                            playbackSpeed =
                                (
                                    (
                                        it *
                                            4
                                        ).roundToInt() /
                                        4f
                                    ).coerceIn(
                                    0.5f,
                                    3f
                                )
                            player.setPlaybackSpeed(
                                playbackSpeed
                            )
                        },
                        onValueChangeFinished = {
                            preferences.edit()
                                .putFloat(
                                    "playback_speed",
                                    playbackSpeed
                                )
                                .apply()
                            saveCurrentVideoState()
                        },
                        valueRange = 0.5f..3f,
                        steps = 9,
                        modifier =
                            Modifier.weight(1f)
                    )
                }

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Text(
                        "Skip silence",
                        color = Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = skipSilence,
                        onCheckedChange = {
                            skipSilence = it
                            player.skipSilenceEnabled =
                                it
                            saveCurrentVideoState()
                        }
                    )

                    Spacer(
                        Modifier.weight(1f)
                    )

                    PresetMenu(
                        current =
                            SilencePreset.matching(
                                silenceThresholdDb,
                                minimumSilence,
                                edgePadding
                            ),
                        onPreset = { preset ->
                            applySilenceSettings(
                                preset.thresholdDb,
                                preset.minimumSilence,
                                preset.edgePadding
                            )
                            gestureText =
                                preset.label
                        }
                    )
                }

                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Brightness6,
                        contentDescription =
                            "Brightness",
                        tint = Color.White
                    )
                    Slider(
                        value = brightness,
                        onValueChange = {
                            setBrightness(it)
                        },
                        modifier =
                            Modifier.weight(1f)
                    )
                }

                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription =
                            "Volume",
                        tint = Color.White
                    )
                    Slider(
                        value = volume,
                        onValueChange = {
                            setVolume(it)
                        },
                        modifier =
                            Modifier.weight(1f)
                    )
                }

                TextButton(
                    onClick = {
                        advancedExpanded =
                            !advancedExpanded
                        controlsVisible = true
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (advancedExpanded) {
                            "Hide advanced"
                        } else {
                            "Advanced"
                        }
                    )
                }

                if (advancedExpanded) {
                    HorizontalDivider()

                    AdvancedPlaybackControls(
                        player = player,
                        currentMs = currentMs,
                        currentUri = currentUri,
                        preferences = preferences,
                        skipSilence = skipSilence,
                        silenceThresholdDb =
                            silenceThresholdDb,
                        onThresholdChange = {
                            silenceThresholdDb = it
                        },
                        minimumSilence =
                            minimumSilence,
                        onMinimumChange = {
                            minimumSilence = it
                        },
                        edgePadding = edgePadding,
                        onPaddingChange = {
                            edgePadding = it
                        },
                        onApplySilence = {
                            applySilenceSettings()
                        },
                        doubleTapSeconds =
                            doubleTapSeconds,
                        onDoubleTapChange = {
                            doubleTapSeconds = it
                            preferences.edit()
                                .putInt(
                                    "double_tap_seconds",
                                    it
                                )
                                .apply()
                        },
                        selectedSubtitleName =
                            subtitleName,
                        onLoadSubtitle = {
                            subtitlePicker.launch(
                                arrayOf(
                                    "text/*",
                                    "application/x-subrip",
                                    "application/ttml+xml",
                                    "application/octet-stream"
                                )
                            )
                        },
                        onRemoveExternalSubtitle = {
                            selectedSubtitleUri =
                                null
                            subtitleName = null
                            subtitleCues =
                                emptyList()
                            currentUri?.let {
                                preferences.edit()
                                    .remove(
                                        "${mediaKey(it)}_subtitle"
                                    )
                                    .apply()
                            }
                        },
                        currentTracks =
                            currentTracks,
                        subtitleOffsetMs =
                            subtitleOffsetMs,
                        onSubtitleOffset = {
                            subtitleOffsetMs = it
                            preferences.edit()
                                .putLong(
                                    "subtitle_offset_ms",
                                    it
                                )
                                .apply()
                        },
                        subtitleFontSize =
                            subtitleFontSize,
                        onSubtitleFontSize = {
                            subtitleFontSize = it
                            preferences.edit()
                                .putFloat(
                                    "subtitle_font_sp",
                                    it
                                )
                                .apply()
                        },
                        subtitleBottomFraction =
                            subtitleBottomFraction,
                        onSubtitleBottomFraction = {
                            subtitleBottomFraction =
                                it
                            preferences.edit()
                                .putFloat(
                                    "subtitle_bottom_fraction",
                                    it
                                )
                                .apply()
                        },
                        subtitleBackgroundOpacity =
                            subtitleBackgroundOpacity,
                        onSubtitleBackgroundOpacity = {
                            subtitleBackgroundOpacity =
                                it
                            preferences.edit()
                                .putFloat(
                                    "subtitle_background_opacity",
                                    it
                                )
                                .apply()
                        },
                        subtitleTone =
                            subtitleTone,
                        onSubtitleTone = {
                            subtitleTone = it
                            preferences.edit()
                                .putString(
                                    "subtitle_tone",
                                    it.name
                                )
                                .apply()
                        },
                        scaleMode = scaleMode,
                        onScaleMode = {
                            scaleMode = it
                            preferences.edit()
                                .putString(
                                    "scale_mode",
                                    it.name
                                )
                                .apply()
                        },
                        videoZoom = videoZoom,
                        onVideoZoom = {
                            videoZoom = it
                            preferences.edit()
                                .putFloat(
                                    "video_zoom",
                                    it
                                )
                                .apply()
                        },
                        audioOnly = audioOnly,
                        onAudioOnly = {
                            audioOnly = it
                            preferences.edit()
                                .putBoolean(
                                    "audio_only",
                                    it
                                )
                                .apply()
                        },
                        resumeMode = resumeMode,
                        onResumeMode = {
                            resumeMode = it
                            preferences.edit()
                                .putString(
                                    "resume_mode",
                                    it.name
                                )
                                .apply()
                        },
                        sleepRemainingMs =
                            sleepRemainingMs,
                        onSleepMinutes = { minutes ->
                            sleepDeadlineMs =
                                if (minutes <= 0) {
                                    0L
                                } else {
                                    System
                                        .currentTimeMillis() +
                                        minutes *
                                        60_000L
                                }
                        },
                        abStartMs = abStartMs,
                        abEndMs = abEndMs,
                        onSetA = {
                            abStartMs = currentMs
                            if (
                                abEndMs <=
                                abStartMs
                            ) {
                                abEndMs = -1L
                            }
                        },
                        onSetB = {
                            if (
                                abStartMs >= 0L &&
                                currentMs >
                                abStartMs
                            ) {
                                abEndMs = currentMs
                            }
                        },
                        onClearAB = {
                            abStartMs = -1L
                            abEndMs = -1L
                        },
                        bookmarks = bookmarks,
                        onAddBookmark = {
                            val uri =
                                currentUri
                                    ?: return@AdvancedPlaybackControls
                            val updated =
                                (
                                    bookmarks +
                                        PlaybackBookmark(
                                            currentMs,
                                            "Bookmark ${bookmarks.size + 1}"
                                        )
                                    )
                                    .sortedBy {
                                        it.positionMs
                                    }
                            bookmarks = updated
                            saveBookmarks(
                                preferences,
                                mediaKey(uri),
                                updated
                            )
                        },
                        onJumpBookmark = {
                            player.seekTo(
                                it.positionMs
                            )
                        },
                        onClearBookmarks = {
                            val uri =
                                currentUri
                                    ?: return@AdvancedPlaybackControls
                            bookmarks = emptyList()
                            saveBookmarks(
                                preferences,
                                mediaKey(uri),
                                emptyList()
                            )
                        },
                        onReset = {
                            silenceThresholdDb =
                                -42f
                            minimumSilence =
                                0.45f
                            edgePadding = 0.08f
                            playbackSpeed = 1f
                            doubleTapSeconds = 10
                            skipSilence = true
                            subtitleOffsetMs = 0L
                            subtitleFontSize = 24f
                            subtitleBottomFraction =
                                0.10f
                            subtitleBackgroundOpacity =
                                0.65f
                            subtitleTone =
                                SubtitleTone.WHITE
                            scaleMode =
                                VideoScaleMode.FIT
                            videoZoom = 1f
                            audioOnly = false
                            resumeMode =
                                ResumeMode.ALWAYS

                            preferences.edit()
                                .putFloat(
                                    "silence_threshold_db",
                                    -42f
                                )
                                .putFloat(
                                    "minimum_silence",
                                    0.45f
                                )
                                .putFloat(
                                    "edge_padding",
                                    0.08f
                                )
                                .putFloat(
                                    "playback_speed",
                                    1f
                                )
                                .putInt(
                                    "double_tap_seconds",
                                    10
                                )
                                .putBoolean(
                                    "skip_silence",
                                    true
                                )
                                .putLong(
                                    "subtitle_offset_ms",
                                    0L
                                )
                                .putFloat(
                                    "subtitle_font_sp",
                                    24f
                                )
                                .putFloat(
                                    "subtitle_bottom_fraction",
                                    0.10f
                                )
                                .putFloat(
                                    "subtitle_background_opacity",
                                    0.65f
                                )
                                .putString(
                                    "subtitle_tone",
                                    SubtitleTone.WHITE.name
                                )
                                .putString(
                                    "scale_mode",
                                    VideoScaleMode.FIT.name
                                )
                                .putFloat(
                                    "video_zoom",
                                    1f
                                )
                                .putBoolean(
                                    "audio_only",
                                    false
                                )
                                .putString(
                                    "resume_mode",
                                    ResumeMode.ALWAYS.name
                                )
                                .apply()

                            PlaybackService.reconfigure(
                                -42f,
                                0.45f,
                                0.08f
                            )
                            player.skipSilenceEnabled =
                                true
                            player.setPlaybackSpeed(
                                1f
                            )
                            gestureText =
                                "Defaults restored"
                        }
                    )
                }

                Text(
                    if (gestureLocked) {
                        "Gestures locked · tap lock to unlock"
                    } else {
                        "Double-tap: ±$doubleTapSeconds s · horizontal swipe: seek · vertical swipe: brightness/volume"
                    },
                    color =
                        Color.White.copy(
                            alpha = 0.58f
                        ),
                    style =
                        MaterialTheme.typography
                            .labelSmall
                )
            }
        }
    }
}

@Composable
private fun AdvancedPlaybackControls(
    player: Player,
    currentMs: Long,
    currentUri: Uri?,
    preferences: android.content.SharedPreferences,
    skipSilence: Boolean,
    silenceThresholdDb: Float,
    onThresholdChange: (Float) -> Unit,
    minimumSilence: Float,
    onMinimumChange: (Float) -> Unit,
    edgePadding: Float,
    onPaddingChange: (Float) -> Unit,
    onApplySilence: () -> Unit,
    doubleTapSeconds: Int,
    onDoubleTapChange: (Int) -> Unit,
    selectedSubtitleName: String?,
    onLoadSubtitle: () -> Unit,
    onRemoveExternalSubtitle: () -> Unit,
    currentTracks: Tracks?,
    subtitleOffsetMs: Long,
    onSubtitleOffset: (Long) -> Unit,
    subtitleFontSize: Float,
    onSubtitleFontSize: (Float) -> Unit,
    subtitleBottomFraction: Float,
    onSubtitleBottomFraction: (Float) -> Unit,
    subtitleBackgroundOpacity: Float,
    onSubtitleBackgroundOpacity: (Float) -> Unit,
    subtitleTone: SubtitleTone,
    onSubtitleTone: (SubtitleTone) -> Unit,
    scaleMode: VideoScaleMode,
    onScaleMode: (VideoScaleMode) -> Unit,
    videoZoom: Float,
    onVideoZoom: (Float) -> Unit,
    audioOnly: Boolean,
    onAudioOnly: (Boolean) -> Unit,
    resumeMode: ResumeMode,
    onResumeMode: (ResumeMode) -> Unit,
    sleepRemainingMs: Long,
    onSleepMinutes: (Long) -> Unit,
    abStartMs: Long,
    abEndMs: Long,
    onSetA: () -> Unit,
    onSetB: () -> Unit,
    onClearAB: () -> Unit,
    bookmarks: List<PlaybackBookmark>,
    onAddBookmark: () -> Unit,
    onJumpBookmark: (PlaybackBookmark) -> Unit,
    onClearBookmarks: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Text(
            "Threshold ${silenceThresholdDb.roundToInt()} dB",
            color = Color.White,
            style =
                MaterialTheme.typography
                    .labelMedium,
            modifier = Modifier.width(125.dp)
        )
        Slider(
            value = silenceThresholdDb,
            onValueChange = onThresholdChange,
            onValueChangeFinished =
                onApplySilence,
            valueRange = -60f..-20f,
            steps = 39,
            enabled = skipSilence,
            modifier = Modifier.weight(1f)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Text(
            "Min ${String.format("%.2f", minimumSilence)} s",
            color = Color.White,
            style =
                MaterialTheme.typography
                    .labelMedium,
            modifier = Modifier.width(125.dp)
        )
        Slider(
            value = minimumSilence,
            onValueChange = {
                onMinimumChange(
                    (
                        (
                            it *
                                20
                            ).roundToInt() /
                            20f
                        )
                )
            },
            onValueChangeFinished =
                onApplySilence,
            valueRange = 0.2f..2f,
            steps = 35,
            enabled = skipSilence,
            modifier = Modifier.weight(1f)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Text(
            "Edge ${(edgePadding * 1000).roundToInt()} ms",
            color = Color.White,
            style =
                MaterialTheme.typography
                    .labelMedium,
            modifier = Modifier.width(125.dp)
        )
        Slider(
            value = edgePadding,
            onValueChange = {
                onPaddingChange(
                    (
                        (
                            it *
                                100
                            ).roundToInt() /
                            100f
                        )
                )
            },
            onValueChangeFinished =
                onApplySilence,
            valueRange = 0.02f..0.20f,
            steps = 17,
            enabled = skipSilence,
            modifier = Modifier.weight(1f)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        DoubleTapMenu(
            seconds = doubleTapSeconds,
            onSelect = onDoubleTapChange
        )
        Spacer(Modifier.width(8.dp))
        ResumeModeMenu(
            mode = resumeMode,
            onSelect = onResumeMode
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Text(
            "Audio-only",
            color = Color.White
        )
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = audioOnly,
            onCheckedChange = onAudioOnly
        )
        Spacer(Modifier.weight(1f))
        VideoScaleMenu(
            mode = scaleMode,
            onSelect = onScaleMode
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Text(
            "Zoom ${String.format("%.1f", videoZoom)}×",
            color = Color.White,
            style =
                MaterialTheme.typography
                    .labelMedium,
            modifier = Modifier.width(105.dp)
        )
        Slider(
            value = videoZoom,
            onValueChange = onVideoZoom,
            valueRange = 1f..3f,
            steps = 19,
            modifier = Modifier.weight(1f)
        )
    }

    HorizontalDivider()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onLoadSubtitle,
            enabled = currentUri != null,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                selectedSubtitleName
                    ?.let { "Subtitle: $it" }
                    ?: "Import subtitle",
                maxLines = 1
            )
        }

        if (selectedSubtitleName != null) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick =
                    onRemoveExternalSubtitle
            ) {
                Text("Remove")
            }
        }
    }

    TrackMenu(
        title = "Audio",
        tracks = currentTracks,
        trackType = C.TRACK_TYPE_AUDIO,
        player = player,
        includeOff = false
    )

    TrackMenu(
        title = "Embedded subtitles",
        tracks = currentTracks,
        trackType = C.TRACK_TYPE_TEXT,
        player = player,
        includeOff = true
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Text(
            "Subtitle offset ${formatSignedSeconds(subtitleOffsetMs)}",
            color = Color.White,
            style =
                MaterialTheme.typography
                    .labelMedium,
            modifier = Modifier.width(145.dp)
        )
        Slider(
            value =
                subtitleOffsetMs.toFloat(),
            onValueChange = {
                onSubtitleOffset(
                    (
                        it /
                            100f
                        ).roundToInt() *
                        100L
                )
            },
            valueRange =
                -10_000f..10_000f,
            steps = 199,
            modifier = Modifier.weight(1f)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Text(
            "Subtitle size ${subtitleFontSize.roundToInt()}",
            color = Color.White,
            style =
                MaterialTheme.typography
                    .labelMedium,
            modifier = Modifier.width(145.dp)
        )
        Slider(
            value = subtitleFontSize,
            onValueChange = onSubtitleFontSize,
            valueRange = 14f..40f,
            steps = 25,
            modifier = Modifier.weight(1f)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Text(
            "Subtitle height ${(subtitleBottomFraction * 100).roundToInt()}%",
            color = Color.White,
            style =
                MaterialTheme.typography
                    .labelMedium,
            modifier = Modifier.width(145.dp)
        )
        Slider(
            value = subtitleBottomFraction,
            onValueChange =
                onSubtitleBottomFraction,
            valueRange = 0.02f..0.35f,
            steps = 32,
            modifier = Modifier.weight(1f)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Text(
            "Subtitle bg ${(subtitleBackgroundOpacity * 100).roundToInt()}%",
            color = Color.White,
            style =
                MaterialTheme.typography
                    .labelMedium,
            modifier = Modifier.width(145.dp)
        )
        Slider(
            value = subtitleBackgroundOpacity,
            onValueChange =
                onSubtitleBackgroundOpacity,
            valueRange = 0f..0.9f,
            steps = 8,
            modifier = Modifier.weight(1f)
        )
    }

    SubtitleToneMenu(
        tone = subtitleTone,
        onSelect = onSubtitleTone
    )

    HorizontalDivider()

    SleepTimerMenu(
        remainingMs = sleepRemainingMs,
        onMinutes = onSleepMinutes
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onSetA,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                if (abStartMs >= 0L) {
                    "A ${formatTime(abStartMs)}"
                } else {
                    "Set A"
                }
            )
        }

        OutlinedButton(
            onClick = onSetB,
            enabled = abStartMs >= 0L,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                if (abEndMs > abStartMs) {
                    "B ${formatTime(abEndMs)}"
                } else {
                    "Set B"
                }
            )
        }

        TextButton(
            onClick = onClearAB,
            enabled =
                abStartMs >= 0L ||
                    abEndMs >= 0L
        ) {
            Text("Clear")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onAddBookmark,
            enabled = currentUri != null
        ) {
            Text(
                "Bookmark ${formatTime(currentMs)}"
            )
        }

        Spacer(Modifier.width(8.dp))

        BookmarkMenu(
            bookmarks = bookmarks,
            onJump = onJumpBookmark,
            onClear = onClearBookmarks
        )
    }

    Text(
        "Bookmarks also act as custom chapter markers. Folder playback automatically discovers SRT/VTT/ASS/SSA/TTML files with the same base name.",
        color =
            Color.White.copy(alpha = 0.58f),
        style =
            MaterialTheme.typography
                .labelSmall
    )

    TextButton(
        onClick = onReset,
        modifier =
            Modifier.fillMaxWidth()
    ) {
        Text("Reset player settings")
    }
}

@Composable
private fun OpenMenu(
    onPlaylist: () -> Unit,
    onFolder: () -> Unit
) {
    var expanded by remember {
        mutableStateOf(false)
    }
    Box {
        OutlinedButton(
            onClick = {
                expanded = true
            }
        ) {
            Text("More")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            }
        ) {
            DropdownMenuItem(
                text = {
                    Text("Open playlist")
                },
                onClick = {
                    expanded = false
                    onPlaylist()
                }
            )
            DropdownMenuItem(
                text = {
                    Text("Open folder")
                },
                onClick = {
                    expanded = false
                    onFolder()
                }
            )
        }
    }
}

@Composable
private fun RecentMenu(
    videos: List<RecentVideo>,
    onOpen: (RecentVideo) -> Unit,
    onClear: () -> Unit
) {
    var expanded by remember {
        mutableStateOf(false)
    }
    Box {
        OutlinedButton(
            onClick = {
                expanded = true
            },
            enabled = videos.isNotEmpty()
        ) {
            Text("Recent")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            }
        ) {
            videos.forEach { video ->
                DropdownMenuItem(
                    text = {
                        Text(
                            video.name,
                            maxLines = 1
                        )
                    },
                    onClick = {
                        expanded = false
                        onOpen(video)
                    }
                )
            }
            if (videos.isNotEmpty()) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text("Clear history")
                    },
                    onClick = {
                        expanded = false
                        onClear()
                    }
                )
            }
        }
    }
}

@Composable
private fun PresetMenu(
    current: SilencePreset?,
    onPreset: (SilencePreset) -> Unit
) {
    var expanded by remember {
        mutableStateOf(false)
    }
    Box {
        OutlinedButton(
            onClick = {
                expanded = true
            }
        ) {
            Text(
                current?.label ?: "Custom"
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            }
        ) {
            SilencePreset.entries
                .forEach { preset ->
                    DropdownMenuItem(
                        text = {
                            Text(preset.label)
                        },
                        onClick = {
                            expanded = false
                            onPreset(preset)
                        }
                    )
                }
        }
    }
}

@Composable
private fun DoubleTapMenu(
    seconds: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember {
        mutableStateOf(false)
    }
    Box {
        OutlinedButton(
            onClick = {
                expanded = true
            }
        ) {
            Text("Double-tap: $seconds s")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            }
        ) {
            listOf(5, 10, 15, 30)
                .forEach { value ->
                    DropdownMenuItem(
                        text = {
                            Text("$value seconds")
                        },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        }
                    )
                }
        }
    }
}

@Composable
private fun ResumeModeMenu(
    mode: ResumeMode,
    onSelect: (ResumeMode) -> Unit
) {
    var expanded by remember {
        mutableStateOf(false)
    }
    Box {
        OutlinedButton(
            onClick = {
                expanded = true
            }
        ) {
            Text(mode.label)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            }
        ) {
            ResumeMode.entries.forEach {
                DropdownMenuItem(
                    text = {
                        Text(it.label)
                    },
                    onClick = {
                        expanded = false
                        onSelect(it)
                    }
                )
            }
        }
    }
}

@Composable
private fun VideoScaleMenu(
    mode: VideoScaleMode,
    onSelect: (VideoScaleMode) -> Unit
) {
    var expanded by remember {
        mutableStateOf(false)
    }
    Box {
        OutlinedButton(
            onClick = {
                expanded = true
            }
        ) {
            Text("View: ${mode.label}")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            }
        ) {
            VideoScaleMode.entries.forEach {
                DropdownMenuItem(
                    text = {
                        Text(it.label)
                    },
                    onClick = {
                        expanded = false
                        onSelect(it)
                    }
                )
            }
        }
    }
}

@Composable
private fun SubtitleToneMenu(
    tone: SubtitleTone,
    onSelect: (SubtitleTone) -> Unit
) {
    var expanded by remember {
        mutableStateOf(false)
    }
    Box {
        OutlinedButton(
            onClick = {
                expanded = true
            },
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text(
                "Subtitle color: ${tone.label}"
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            }
        ) {
            SubtitleTone.entries.forEach {
                DropdownMenuItem(
                    text = {
                        Text(it.label)
                    },
                    onClick = {
                        expanded = false
                        onSelect(it)
                    }
                )
            }
        }
    }
}

@Composable
private fun SleepTimerMenu(
    remainingMs: Long,
    onMinutes: (Long) -> Unit
) {
    var expanded by remember {
        mutableStateOf(false)
    }
    Box {
        OutlinedButton(
            onClick = {
                expanded = true
            },
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text(
                if (remainingMs > 0L) {
                    "Sleep timer: ${formatTime(remainingMs)}"
                } else {
                    "Sleep timer"
                }
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            }
        ) {
            listOf(
                15L,
                30L,
                60L,
                90L
            ).forEach { minutes ->
                DropdownMenuItem(
                    text = {
                        Text("$minutes minutes")
                    },
                    onClick = {
                        expanded = false
                        onMinutes(minutes)
                    }
                )
            }
            DropdownMenuItem(
                text = {
                    Text("Off")
                },
                onClick = {
                    expanded = false
                    onMinutes(0L)
                }
            )
        }
    }
}

@Composable
private fun BookmarkMenu(
    bookmarks: List<PlaybackBookmark>,
    onJump: (PlaybackBookmark) -> Unit,
    onClear: () -> Unit
) {
    var expanded by remember {
        mutableStateOf(false)
    }
    Box {
        OutlinedButton(
            onClick = {
                expanded = true
            },
            enabled = bookmarks.isNotEmpty()
        ) {
            Text(
                "Bookmarks (${bookmarks.size})"
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            }
        ) {
            bookmarks.forEach {
                DropdownMenuItem(
                    text = {
                        Text(
                            "${it.name} · ${formatTime(it.positionMs)}"
                        )
                    },
                    onClick = {
                        expanded = false
                        onJump(it)
                    }
                )
            }
            if (bookmarks.isNotEmpty()) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text("Clear bookmarks")
                    },
                    onClick = {
                        expanded = false
                        onClear()
                    }
                )
            }
        }
    }
}

@Composable
private fun TrackMenu(
    title: String,
    tracks: Tracks?,
    trackType: Int,
    player: Player,
    includeOff: Boolean
) {
    val groups =
        tracks?.groups
            ?.filter {
                it.type == trackType
            }
            .orEmpty()

    var expanded by remember {
        mutableStateOf(false)
    }

    val selectedName =
        groups.firstNotNullOfOrNull { group ->
            (
                0 until group.length
                )
                .firstOrNull {
                    group.isTrackSelected(it)
                }
                ?.let {
                    trackLabel(group, it)
                }
        } ?: if (includeOff) {
            "Off/Auto"
        } else {
            "Auto"
        }

    Box {
        OutlinedButton(
            onClick = {
                expanded = true
            },
            enabled =
                groups.isNotEmpty() ||
                    includeOff,
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text(
                "$title: $selectedName",
                maxLines = 1
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            }
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (includeOff) {
                            "Off"
                        } else {
                            "Auto"
                        }
                    )
                },
                onClick = {
                    expanded = false
                    val builder =
                        player
                            .trackSelectionParameters
                            .buildUpon()
                            .clearOverridesOfType(
                                trackType
                            )
                            .setTrackTypeDisabled(
                                trackType,
                                includeOff
                            )
                    player
                        .trackSelectionParameters =
                        builder.build()
                }
            )

            groups.forEach { group ->
                for (
                    index in
                    0 until group.length
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                trackLabel(
                                    group,
                                    index
                                )
                            )
                        },
                        onClick = {
                            expanded = false
                            player
                                .trackSelectionParameters =
                                player
                                    .trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(
                                        trackType,
                                        false
                                    )
                                    .setOverrideForType(
                                        TrackSelectionOverride(
                                            group
                                                .mediaTrackGroup,
                                            index
                                        )
                                    )
                                    .build()
                        }
                    )
                }
            }
        }
    }
}

private fun trackLabel(
    group: Tracks.Group,
    index: Int
): String {
    val format =
        group.getTrackFormat(index)
    val base =
        format.label
            ?: format.language?.uppercase()
            ?: "Track ${index + 1}"

    return if (
        group.type ==
        C.TRACK_TYPE_AUDIO
    ) {
        val channels =
            format.channelCount
                .takeIf { it > 0 }
                ?.let { " · ${it}ch" }
                ?: ""
        "$base$channels"
    } else {
        base
    }
}

private fun buildMediaItem(
    uri: Uri,
    name: String
): MediaItem {
    return MediaItem.Builder()
        .setMediaId(uri.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .build()
        )
        .build()
}

private fun readablePlaybackError(
    error: PlaybackException
): String {
    val detail =
        error.cause?.message
            ?: error.message
            ?: "Unknown playback failure"

    return "${error.errorCodeName}: $detail"
}

private fun scanVideoFolder(
    context: Context,
    treeUri: Uri
): List<PlaylistEntry> {
    val root =
        DocumentFile.fromTreeUri(
            context,
            treeUri
        ) ?: return emptyList()

    val allFiles =
        mutableListOf<DocumentFile>()

    fun walk(file: DocumentFile) {
        if (file.isDirectory) {
            file.listFiles()
                .forEach(::walk)
        } else if (file.isFile) {
            allFiles += file
        }
    }

    walk(root)

    val subtitleByBase =
        allFiles
            .filter(::isSubtitleFile)
            .associateBy {
                baseName(
                    it.name.orEmpty()
                ).lowercase()
            }

    return allFiles
        .filter(::isVideoFile)
        .sortedBy {
            it.name.orEmpty()
                .lowercase()
        }
        .mapNotNull { video ->
            val uri = video.uri
            val name =
                video.name ?: "Video"
            val subtitle =
                subtitleByBase[
                    baseName(name)
                        .lowercase()
                ]?.uri

            PlaylistEntry(
                uri = uri,
                name = name,
                suggestedSubtitleUri =
                    subtitle
            )
        }
}

private fun findSiblingSubtitle(
    context: Context,
    videoUri: Uri
): Uri? {
    val name =
        queryDisplayName(
            context,
            videoUri
        )
    val targetBase =
        baseName(name).lowercase()

    val parentString =
        videoUri.toString()
            .substringBeforeLast(
                '/',
                ""
            )

    if (parentString.isBlank()) {
        return null
    }

    val parentUri =
        runCatching {
            Uri.parse(parentString)
        }.getOrNull()
            ?: return null

    val parent =
        DocumentFile.fromSingleUri(
            context,
            parentUri
        )

    if (
        parent == null ||
        !parent.isDirectory
    ) {
        return null
    }

    return parent.listFiles()
        .firstOrNull {
            isSubtitleFile(it) &&
                baseName(
                    it.name.orEmpty()
                ).lowercase() ==
                targetBase
        }
        ?.uri
}

private fun isVideoFile(
    file: DocumentFile
): Boolean {
    val type =
        file.type.orEmpty()
    if (type.startsWith("video/")) {
        return true
    }

    return file.name
        .orEmpty()
        .substringAfterLast(
            '.',
            ""
        )
        .lowercase() in
        setOf(
            "mp4",
            "mkv",
            "webm",
            "mov",
            "m4v",
            "avi",
            "ts",
            "mts",
            "m2ts",
            "3gp"
        )
}

private fun isSubtitleFile(
    file: DocumentFile
): Boolean {
    val extension =
        file.name
            .orEmpty()
            .substringAfterLast(
                '.',
                ""
            )
            .lowercase()

    return extension in
        setOf(
            "srt",
            "vtt",
            "ass",
            "ssa",
            "ttml",
            "xml"
        )
}

private fun baseName(
    name: String
): String =
    name.substringBeforeLast(
        '.',
        name
    )

@Suppress("DEPRECATION")
private fun extractIntentUris(
    intent: Intent?
): List<Uri> {
    if (intent == null) {
        return emptyList()
    }

    val result =
        linkedSetOf<Uri>()

    intent.data?.let(result::add)

    intent.clipData?.let { clip ->
        for (
            index in
            0 until clip.itemCount
        ) {
            clip.getItemAt(index)
                .uri
                ?.let(result::add)
        }
    }

    when (intent.action) {
        Intent.ACTION_SEND -> {
            (
                intent.getParcelableExtra(
                    Intent.EXTRA_STREAM
                ) as? Uri
                )?.let(result::add)
        }

        Intent.ACTION_SEND_MULTIPLE -> {
            intent.getParcelableArrayListExtra<Uri>(
                Intent.EXTRA_STREAM
            )?.let(result::addAll)
        }
    }

    return result.toList()
}

private fun mediaKey(
    uri: Uri
): String =
    "media_" +
        uri.toString()
            .hashCode()
            .toUInt()
            .toString(16)

private fun takePersistableReadPermission(
    activity: Activity,
    uri: Uri
) {
    try {
        activity
            .contentResolver
            .takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
    } catch (_: Exception) {
        // ACTION_VIEW providers and some document providers grant
        // access only for the current process/session.
    }
}

private fun queryDisplayName(
    context: Context,
    uri: Uri
): String {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val index =
                cursor.getColumnIndex(
                    OpenableColumns
                        .DISPLAY_NAME
                )
            if (
                index >= 0 &&
                cursor.moveToFirst()
            ) {
                cursor.getString(index)
            } else {
                null
            }
        } ?: uri.lastPathSegment
            ?.substringAfterLast('/')
            ?: "Video"
    } catch (_: Exception) {
        uri.lastPathSegment
            ?.substringAfterLast('/')
            ?: "Video"
    }
}

private fun queryFileSize(
    context: Context,
    uri: Uri
): String {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(
                OpenableColumns.SIZE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val index =
                cursor.getColumnIndex(
                    OpenableColumns.SIZE
                )
            if (
                index >= 0 &&
                cursor.moveToFirst() &&
                !cursor.isNull(index)
            ) {
                formatBytes(
                    cursor.getLong(index)
                )
            } else {
                ""
            }
        } ?: ""
    } catch (_: Exception) {
        ""
    }
}

private fun formatBytes(
    bytes: Long
): String {
    if (bytes <= 0L) {
        return ""
    }

    val mb =
        bytes /
            (1024.0 * 1024.0)

    return if (mb >= 1024.0) {
        String.format(
            "%.2f GB",
            mb / 1024.0
        )
    } else {
        String.format(
            "%.1f MB",
            mb
        )
    }
}

private fun loadRecentVideos(
    preferences:
        android.content.SharedPreferences
): List<RecentVideo> {
    val raw =
        preferences.getString(
            "recent_videos",
            null
        ) ?: return emptyList()

    return try {
        val array = JSONArray(raw)
        buildList {
            for (
                index in
                0 until array.length()
            ) {
                val item =
                    array.optJSONObject(index)
                        ?: continue
                val uri =
                    item.optString("uri")
                if (uri.isBlank()) {
                    continue
                }

                add(
                    RecentVideo(
                        uri = uri,
                        name =
                            item.optString(
                                "name",
                                "Video"
                            )
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveRecentVideos(
    preferences:
        android.content.SharedPreferences,
    videos: List<RecentVideo>
) {
    val array = JSONArray()
    videos.take(12)
        .forEach { video ->
            array.put(
                JSONObject()
                    .put(
                        "uri",
                        video.uri
                    )
                    .put(
                        "name",
                        video.name
                    )
            )
        }
    preferences.edit()
        .putString(
            "recent_videos",
            array.toString()
        )
        .apply()
}

private fun loadBookmarks(
    preferences:
        android.content.SharedPreferences,
    key: String
): List<PlaybackBookmark> {
    val raw =
        preferences.getString(
            "${key}_bookmarks",
            null
        ) ?: return emptyList()

    return try {
        val array = JSONArray(raw)
        buildList {
            for (
                index in
                0 until array.length()
            ) {
                val item =
                    array.optJSONObject(index)
                        ?: continue
                add(
                    PlaybackBookmark(
                        positionMs =
                            item.optLong(
                                "position"
                            ),
                        name =
                            item.optString(
                                "name",
                                "Bookmark"
                            )
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveBookmarks(
    preferences:
        android.content.SharedPreferences,
    key: String,
    bookmarks:
        List<PlaybackBookmark>
) {
    val array = JSONArray()
    bookmarks.forEach {
        array.put(
            JSONObject()
                .put(
                    "position",
                    it.positionMs
                )
                .put(
                    "name",
                    it.name
                )
        )
    }
    preferences.edit()
        .putString(
            "${key}_bookmarks",
            array.toString()
        )
        .apply()
}

private fun formatSignedSeconds(
    milliseconds: Long
): String {
    val seconds =
        milliseconds / 1000.0
    return String.format(
        "%+.1f s",
        seconds
    )
}

private fun formatTime(
    ms: Long
): String {
    val totalSeconds =
        ms.coerceAtLeast(0L) /
            1000L
    val hours =
        totalSeconds / 3600L
    val minutes =
        (
            totalSeconds %
                3600L
            ) / 60L
    val seconds =
        totalSeconds % 60L

    return if (hours > 0L) {
        String.format(
            "%d:%02d:%02d",
            hours,
            minutes,
            seconds
        )
    } else {
        String.format(
            "%d:%02d",
            minutes,
            seconds
        )
    }
}
