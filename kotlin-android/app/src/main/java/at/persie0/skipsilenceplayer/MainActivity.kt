@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package at.persie0.skipsilenceplayer

import androidx.compose.ui.res.stringResource

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    var pipMode by mutableStateOf(false)
        private set
    var controller by mutableStateOf<MediaController?>(null)
        private set
    var incomingUri by mutableStateOf<Uri?>(null)
        private set

    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handleOpenIntent(intent)

        val sessionToken = SessionToken(
            this,
            ComponentName(this, PlaybackService::class.java)
        )
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { controller = it }
            },
            ContextCompat.getMainExecutor(this)
        )

        setContent {
            MaterialTheme {
                val player = controller
                if (player == null) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.ui_starting_player), color = Color.White)
                    }
                } else {
                    SkipSilencePlayerScreen(this, player)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenIntent(intent)
    }

    private fun handleOpenIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            incomingUri = intent.data
        }
    }

    fun consumeIncomingUri() {
        incomingUri = null
    }

    override fun onDestroy() {
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        controller = null
        super.onDestroy()
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
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(
                Rational(
                    width.coerceAtLeast(16),
                    height.coerceAtLeast(9)
                )
            )
            .build()
        enterPictureInPictureMode(params)
    }

    fun setPlayerFullscreen(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

private data class VideoEntry(
    val uri: Uri,
    val name: String
)

private data class RecentVideo(
    val uri: String,
    val name: String
)

private data class BookmarkPoint(
    val positionMs: Long,
    val label: String
)

private data class PendingResume(
    val entries: List<VideoEntry>,
    val startIndex: Int,
    val positionMs: Long
)

private enum class ResumeMode(val label: String) {
    ASK("Ask"),
    ALWAYS("Always"),
    NEVER("Never")
}

private enum class AspectMode(val label: String, val resizeMode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Fill", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    CROP("Crop", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    ORIGINAL("Original", AspectRatioFrameLayout.RESIZE_MODE_FIT)
}

private enum class SubtitlePosition(val label: String) {
    TOP("Top"),
    CENTER("Center"),
    BOTTOM("Bottom")
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
        fun matching(threshold: Float, minimum: Float, padding: Float): SilencePreset? {
            return entries.firstOrNull {
                abs(it.thresholdDb - threshold) < 0.01f &&
                    abs(it.minimumSilence - minimum) < 0.01f &&
                    abs(it.edgePadding - padding) < 0.01f
            }
        }
    }
}

@Composable
private fun SkipSilencePlayerScreen(
    activity: MainActivity,
    player: MediaController
) {
    val context = activity.applicationContext
    val scope = rememberCoroutineScope()
    val preferences = remember {
        context.getSharedPreferences("skip_silence_player", Context.MODE_PRIVATE)
    }
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
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
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
        )
    }

    var skipSilence by remember {
        mutableStateOf(preferences.getBoolean("skip_silence", true))
    }
    var silenceThresholdDb by remember {
        mutableFloatStateOf(preferences.getFloat("silence_threshold_db", -42f))
    }
    var minimumSilence by remember {
        mutableFloatStateOf(preferences.getFloat("minimum_silence", 0.45f))
    }
    var edgePadding by remember {
        mutableFloatStateOf(preferences.getFloat("edge_padding", 0.08f))
    }
    var playbackSpeed by remember {
        mutableFloatStateOf(preferences.getFloat("playback_speed", 1f))
    }
    var doubleTapSeconds by remember {
        mutableIntStateOf(preferences.getInt("double_tap_seconds", 10))
    }

    var resumeMode by remember {
        mutableStateOf(
            runCatching {
                ResumeMode.valueOf(
                    preferences.getString("resume_mode", ResumeMode.ASK.name)
                        ?: ResumeMode.ASK.name
                )
            }.getOrDefault(ResumeMode.ASK)
        )
    }
    var pendingResume by remember { mutableStateOf<PendingResume?>(null) }

    var fileName by remember { mutableStateOf("No video selected") }
    var fileInfo by remember { mutableStateOf("") }
    var currentMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentTracks by remember { mutableStateOf<Tracks?>(null) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var activeUri by remember { mutableStateOf<Uri?>(null) }
    var activeMediaId by remember { mutableStateOf("") }

    var recentVideos by remember {
        mutableStateOf(loadRecentVideos(preferences))
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var gestureLocked by remember { mutableStateOf(false) }
    var gestureText by remember { mutableStateOf<String?>(null) }

    var aspectMode by remember {
        mutableStateOf(
            runCatching {
                AspectMode.valueOf(
                    preferences.getString("aspect_mode", AspectMode.FIT.name)
                        ?: AspectMode.FIT.name
                )
            }.getOrDefault(AspectMode.FIT)
        )
    }
    var videoZoom by remember {
        mutableFloatStateOf(preferences.getFloat("video_zoom", 1f).coerceIn(1f, 3f))
    }

    var audioOnly by remember {
        mutableStateOf(preferences.getBoolean("audio_only", false))
    }

    var selectedSubtitleUri by remember { mutableStateOf<Uri?>(null) }
    var subtitleName by remember { mutableStateOf<String?>(null) }
    var externalSubtitleCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var externalSubtitleText by remember { mutableStateOf("") }
    var subtitleOffsetMs by remember {
        mutableLongStateOf(preferences.getLong("subtitle_offset_ms", 0L))
    }
    var subtitleFontScale by remember {
        mutableFloatStateOf(preferences.getFloat("subtitle_font_scale", 1f).coerceIn(0.7f, 2f))
    }
    var subtitleBackgroundOpacity by remember {
        mutableFloatStateOf(
            preferences.getFloat("subtitle_background_opacity", 0.72f)
                .coerceIn(0f, 1f)
        )
    }
    var subtitlePosition by remember {
        mutableStateOf(
            runCatching {
                SubtitlePosition.valueOf(
                    preferences.getString(
                        "subtitle_position",
                        SubtitlePosition.BOTTOM.name
                    ) ?: SubtitlePosition.BOTTOM.name
                )
            }.getOrDefault(SubtitlePosition.BOTTOM)
        )
    }
    var subtitleSearch by remember { mutableStateOf("") }

    var bookmarks by remember { mutableStateOf<List<BookmarkPoint>>(emptyList()) }
    var abStartMs by remember { mutableStateOf<Long?>(null) }
    var abEndMs by remember { mutableStateOf<Long?>(null) }

    var sleepDeadline by remember { mutableStateOf<Long?>(null) }
    var sleepAtEnd by remember { mutableStateOf(false) }

    var skippedSeconds by remember { mutableFloatStateOf(0f) }
    var estimatedSkippedSeconds by remember { mutableFloatStateOf(0f) }
    var estimatedWatchSeconds by remember { mutableFloatStateOf(0f) }

    val latestBrightness = rememberUpdatedState(brightness)
    val latestVolume = rememberUpdatedState(volume)
    val latestDoubleTap = rememberUpdatedState(doubleTapSeconds)
    val latestGestureLocked = rememberUpdatedState(gestureLocked)

    val transformState = rememberTransformableState { zoomChange, _, _ ->
        if (!latestGestureLocked.value) {
            videoZoom = (videoZoom * zoomChange).coerceIn(1f, 3f)
        }
    }

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
            (clamped * maxVolume).roundToInt().coerceIn(0, maxVolume),
            0
        )
    }

    fun saveCurrentState() {
        val uri = activeUri ?: return
        val key = mediaKey(uri)
        preferences.edit()
            .putLong("${key}_position", player.currentPosition.coerceAtLeast(0L))
            .putFloat("${key}_speed", playbackSpeed)
            .putFloat("${key}_threshold", silenceThresholdDb)
            .putFloat("${key}_minimum", minimumSilence)
            .putFloat("${key}_padding", edgePadding)
            .putBoolean("${key}_skip", skipSilence)
            .putString("${key}_subtitle", selectedSubtitleUri?.toString())
            .apply()
    }

    fun loadExternalSubtitle(uri: Uri?) {
        selectedSubtitleUri = uri
        subtitleName = uri?.let { queryDisplayName(context, it) }

        if (uri == null) {
            externalSubtitleCues = emptyList()
            externalSubtitleText = ""
            return
        }

        scope.launch {
            externalSubtitleCues = withContext(Dispatchers.IO) {
                SubtitleParser.parse(context, uri)
            }
            gestureText = if (externalSubtitleCues.isEmpty()) {
                "No subtitle cues found"
            } else {
                "${externalSubtitleCues.size} subtitle cues loaded"
            }
        }
    }

    fun applyVideoPreferences(uri: Uri) {
        val key = mediaKey(uri)
        val newThreshold = preferences.getFloat(
            "${key}_threshold",
            preferences.getFloat("silence_threshold_db", -42f)
        )
        val newMinimum = preferences.getFloat(
            "${key}_minimum",
            preferences.getFloat("minimum_silence", 0.45f)
        )
        val newPadding = preferences.getFloat(
            "${key}_padding",
            preferences.getFloat("edge_padding", 0.08f)
        )

        silenceThresholdDb = newThreshold
        minimumSilence = newMinimum
        edgePadding = newPadding
        playbackSpeed = preferences.getFloat(
            "${key}_speed",
            preferences.getFloat("playback_speed", 1f)
        )
        skipSilence = preferences.getBoolean(
            "${key}_skip",
            preferences.getBoolean("skip_silence", true)
        )

        PlaybackRuntime.reconfigureSilence(
            SilenceConfig(newThreshold, newMinimum, newPadding)
        )
        player.setPlaybackSpeed(playbackSpeed)
        PlaybackRuntime.setSkipSilenceEnabled(skipSilence)

        val subtitle = preferences.getString("${key}_subtitle", null)
            ?.let(Uri::parse)
        loadExternalSubtitle(subtitle)
        bookmarks = loadBookmarks(preferences, uri)
    }

    fun commitOpen(
        entries: List<VideoEntry>,
        startIndex: Int,
        positionMs: Long
    ) {
        if (entries.isEmpty()) return
        saveCurrentState()

        val index = startIndex.coerceIn(0, entries.lastIndex)
        val selected = entries[index]
        applyVideoPreferences(selected.uri)

        val mediaItems = entries.map {
            MediaItem.Builder()
                .setMediaId(it.uri.toString())
                .setUri(it.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(it.name)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                        .build()
                )
                .build()
        }

        player.setMediaItems(mediaItems, index, positionMs.coerceAtLeast(0L))
        player.prepare()
        player.play()
        controlsVisible = true

        val updated = entries.map {
            RecentVideo(it.uri.toString(), it.name)
        } + recentVideos
        recentVideos = updated.distinctBy { it.uri }.take(12)
        saveRecentVideos(preferences, recentVideos)
    }

    fun requestOpen(entries: List<VideoEntry>, startIndex: Int = 0) {
        if (entries.isEmpty()) return
        val index = startIndex.coerceIn(0, entries.lastIndex)
        val saved = preferences.getLong(
            "${mediaKey(entries[index].uri)}_position",
            0L
        )

        when {
            saved < 5_000L -> commitOpen(entries, index, 0L)
            resumeMode == ResumeMode.ALWAYS -> commitOpen(entries, index, saved)
            resumeMode == ResumeMode.NEVER -> commitOpen(entries, index, 0L)
            else -> pendingResume = PendingResume(entries, index, saved)
        }
    }

    fun applySilenceSettings() {
        silenceThresholdDb = silenceThresholdDb.roundToInt().toFloat()
        minimumSilence = ((minimumSilence * 20f).roundToInt() / 20f)
            .coerceIn(0.2f, 2f)
        edgePadding = ((edgePadding * 100f).roundToInt() / 100f)
            .coerceIn(0.02f, 0.20f)

        preferences.edit()
            .putFloat("silence_threshold_db", silenceThresholdDb)
            .putFloat("minimum_silence", minimumSilence)
            .putFloat("edge_padding", edgePadding)
            .apply()
        saveCurrentState()
        PlaybackRuntime.reconfigureSilence(
            SilenceConfig(
                silenceThresholdDb,
                minimumSilence,
                edgePadding
            )
        )
    }

    fun seekBy(deltaMs: Long) {
        if (player.mediaItemCount == 0) return
        val maxPosition = if (durationMs > 0) durationMs else Long.MAX_VALUE
        player.seekTo(
            (player.currentPosition + deltaMs).coerceIn(0L, maxPosition)
        )
        gestureText = if (deltaMs < 0) {
            "−${abs(deltaMs) / 1000} s"
        } else {
            "+${deltaMs / 1000} s"
        }
        controlsVisible = true
    }

    fun searchSubtitle() {
        val query = subtitleSearch.trim()
        if (query.isEmpty() || externalSubtitleCues.isEmpty()) return

        val after = currentMs - subtitleOffsetMs + 1
        val match = externalSubtitleCues.firstOrNull {
            it.startMs >= after && it.text.contains(query, ignoreCase = true)
        } ?: externalSubtitleCues.firstOrNull {
            it.text.contains(query, ignoreCase = true)
        }

        if (match != null) {
            player.seekTo((match.startMs + subtitleOffsetMs).coerceAtLeast(0L))
            gestureText = "Subtitle match"
        } else {
            gestureText = "No subtitle match"
        }
    }

    fun addBookmark() {
        val uri = activeUri ?: return
        val position = player.currentPosition.coerceAtLeast(0L)
        val item = BookmarkPoint(
            positionMs = position,
            label = formatTime(position)
        )
        bookmarks = (bookmarks + item)
            .distinctBy { it.positionMs / 1000L }
            .sortedBy { it.positionMs }
        saveBookmarks(preferences, uri, bookmarks)
        gestureText = "Bookmark ${item.label}"
    }

    LaunchedEffect(activity.incomingUri) {
        val uri = activity.incomingUri
        if (uri != null) {
            requestOpen(
                listOf(
                    VideoEntry(uri, queryDisplayName(context, uri))
                )
            )
            activity.consumeIncomingUri()
        }
    }

    LaunchedEffect(player, PlaybackRuntime.generation) {
        PlaybackRuntime.setSkipSilenceEnabled(skipSilence)
        player.setPlaybackSpeed(playbackSpeed)
    }

    LaunchedEffect(audioOnly, player) {
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
                .build()
        preferences.edit().putBoolean("audio_only", audioOnly).apply()
    }

    LaunchedEffect(player) {
        var persistenceTick = 0
        while (isActive) {
            currentMs = player.currentPosition.coerceAtLeast(0L)
            val rawDuration = player.duration
            durationMs =
                if (rawDuration != C.TIME_UNSET && rawDuration > 0) rawDuration else 0L
            isPlaying = player.isPlaying
            currentTracks = player.currentTracks
            playerError = player.playerError?.let {
                "${it.errorCodeName}: ${it.localizedMessage ?: "Playback failed"}"
            }

            val currentItem = player.currentMediaItem
            val uri = currentItem?.localConfiguration?.uri
            val mediaId = currentItem?.mediaId.orEmpty()
            if (uri != null && mediaId != activeMediaId) {
                saveCurrentState()
                activeMediaId = mediaId
                activeUri = uri
                fileName = currentItem.mediaMetadata.title?.toString()
                    ?: queryDisplayName(context, uri)
                fileInfo = queryFileSize(context, uri)
                bookmarks = loadBookmarks(preferences, uri)

                val storedSubtitle =
                    preferences.getString("${mediaKey(uri)}_subtitle", null)
                        ?.let(Uri::parse)
                loadExternalSubtitle(storedSubtitle)

                if (resumeMode == ResumeMode.ALWAYS && currentMs < 2_000L) {
                    val saved = preferences.getLong(
                        "${mediaKey(uri)}_position",
                        0L
                    )
                    if (saved > 5_000L) {
                        player.seekTo(saved)
                    }
                }
            }

            val videoSize = player.videoSize
            val resolution =
                if (videoSize.width > 0 && videoSize.height > 0) {
                    "${videoSize.width}×${videoSize.height}"
                } else {
                    ""
                }
            val sizeText = activeUri?.let { queryFileSize(context, it) }.orEmpty()
            fileInfo = listOf(
                sizeText,
                resolution,
                if (durationMs > 0) formatTime(durationMs) else ""
            ).filter { it.isNotBlank() }.joinToString(" · ")

            skippedSeconds = PlaybackRuntime.skippedSeconds()
            if (durationMs > 0 && currentMs > 30_000L) {
                val predicted =
                    skippedSeconds * (durationMs.toFloat() / currentMs.toFloat())
                estimatedSkippedSeconds = predicted.coerceIn(
                    skippedSeconds,
                    durationMs / 1000f
                )
            } else {
                estimatedSkippedSeconds = skippedSeconds
            }
            estimatedWatchSeconds =
                ((durationMs / 1000f - estimatedSkippedSeconds)
                    .coerceAtLeast(0f) / playbackSpeed.coerceAtLeast(0.5f))

            val subtitleClock = currentMs - subtitleOffsetMs
            externalSubtitleText = externalSubtitleCues.firstOrNull {
                it.startMs <= subtitleClock && subtitleClock < it.endMs
            }?.text.orEmpty()

            val a = abStartMs
            val b = abEndMs
            if (
                a != null &&
                b != null &&
                b > a &&
                player.isPlaying &&
                currentMs >= b
            ) {
                player.seekTo(a)
            }

            val deadline = sleepDeadline
            if (
                deadline != null &&
                SystemClock.elapsedRealtime() >= deadline
            ) {
                player.pause()
                sleepDeadline = null
                gestureText = "Sleep timer finished"
            }
            if (
                sleepAtEnd &&
                durationMs > 0 &&
                currentMs >= durationMs - 500L
            ) {
                player.pause()
                sleepAtEnd = false
            }

            persistenceTick++
            if (persistenceTick >= 5) {
                persistenceTick = 0
                saveCurrentState()
            }
            delay(200)
        }
    }

    LaunchedEffect(gestureText) {
        if (gestureText != null) {
            delay(900)
            gestureText = null
        }
    }

    LaunchedEffect(isPlaying, controlsVisible, isFullscreen, activity.pipMode) {
        if (isPlaying && controlsVisible && !activity.pipMode && !gestureLocked) {
            delay(if (isFullscreen) 2_500L else 4_000L)
            controlsVisible = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            saveCurrentState()
            activity.setPlayerFullscreen(false)
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val entries = uris.map { uri ->
                takePersistableReadPermission(activity, uri)
                VideoEntry(uri, queryDisplayName(context, uri))
            }
            requestOpen(entries)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            takePersistableTreePermission(activity, treeUri)
            scope.launch {
                val entries = withContext(Dispatchers.IO) {
                    collectVideoDocuments(context, treeUri)
                }
                if (entries.isEmpty()) {
                    gestureText = "No videos found in folder"
                } else {
                    requestOpen(entries)
                }
            }
        }
    }

    val subtitlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && activeUri != null) {
            takePersistableReadPermission(activity, uri)
            loadExternalSubtitle(uri)
            preferences.edit()
                .putString(
                    "${mediaKey(activeUri!!)}_subtitle",
                    uri.toString()
                )
                .apply()
        }
    }

    val pointerGestures =
        if (gestureLocked) {
            Modifier
        } else {
            Modifier
                .pointerInput(player, doubleTapSeconds) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                        },
                        onDoubleTap = { offset ->
                            val delta = latestDoubleTap.value * 1000L
                            if (offset.x < size.width / 2f) {
                                seekBy(-delta)
                            } else {
                                seekBy(delta)
                            }
                        }
                    )
                }
                .pointerInput(player) {
                    var startX = 0f
                    var startValue = 0f
                    var totalX = 0f
                    var totalY = 0f
                    var orientation = 0
                    var seekDeltaMs = 0L
                    var startPosition = 0L

                    detectDragGestures(
                        onDragStart = { offset ->
                            startX = offset.x
                            startValue =
                                if (offset.x < size.width / 2f) {
                                    latestBrightness.value
                                } else {
                                    latestVolume.value
                                }
                            totalX = 0f
                            totalY = 0f
                            orientation = 0
                            seekDeltaMs = 0L
                            startPosition = player.currentPosition
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            totalX += dragAmount.x
                            totalY += dragAmount.y

                            if (
                                orientation == 0 &&
                                max(abs(totalX), abs(totalY)) > 18f
                            ) {
                                orientation =
                                    if (abs(totalX) > abs(totalY)) 1 else 2
                            }

                            if (orientation == 1) {
                                val width = max(size.width.toFloat(), 1f)
                                seekDeltaMs =
                                    ((totalX / width) * 120_000f).toLong()
                                        .coerceIn(-120_000L, 120_000L)
                                gestureText =
                                    if (seekDeltaMs >= 0) {
                                        "Seek +${seekDeltaMs / 1000} s"
                                    } else {
                                        "Seek ${seekDeltaMs / 1000} s"
                                    }
                            } else if (orientation == 2) {
                                val height = max(size.height.toFloat(), 1f)
                                val next =
                                    (startValue - totalY / height)
                                        .coerceIn(0f, 1f)
                                if (startX < size.width / 2f) {
                                    setBrightness(next)
                                    gestureText =
                                        "Brightness ${(next * 100).roundToInt()}%"
                                } else {
                                    setVolume(next)
                                    gestureText =
                                        "Volume ${(next * 100).roundToInt()}%"
                                }
                            }
                        },
                        onDragEnd = {
                            if (orientation == 1 && seekDeltaMs != 0L) {
                                val end =
                                    if (durationMs > 0) durationMs else Long.MAX_VALUE
                                player.seekTo(
                                    (startPosition + seekDeltaMs)
                                        .coerceIn(0L, end)
                                )
                            }
                        }
                    )
                }
                .transformable(transformState)
        }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = aspectMode.resizeMode
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    this.player = player
                }
            },
            update = { view ->
                view.player = player
                view.resizeMode = aspectMode.resizeMode
                view.subtitleView?.apply {
                    setApplyEmbeddedFontSizes(false)
                    setFractionalTextSize(
                        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION *
                            subtitleFontScale
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = videoZoom,
                    scaleY = videoZoom
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(pointerGestures)
        )

        if (player.mediaItemCount == 0 && !activity.pipMode) {
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
                },
                modifier = Modifier.align(Alignment.Center)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ui_open_video))
            }
        }

        if (externalSubtitleText.isNotBlank()) {
            val alignment = when (subtitlePosition) {
                SubtitlePosition.TOP -> Alignment.TopCenter
                SubtitlePosition.CENTER -> Alignment.Center
                SubtitlePosition.BOTTOM -> Alignment.BottomCenter
            }
            Text(
                text = externalSubtitleText,
                color = Color.White,
                fontSize = (18f * subtitleFontScale).sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(alignment)
                    .padding(
                        horizontal = 24.dp,
                        vertical = if (
                            subtitlePosition == SubtitlePosition.BOTTOM &&
                            controlsVisible
                        ) 230.dp else 32.dp
                    )
                    .background(
                        Color.Black.copy(alpha = subtitleBackgroundOpacity),
                        MaterialTheme.shapes.small
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }

        gestureText?.let { message ->
            if (!activity.pipMode) {
                Text(
                    text = message,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(
                            Color.Black.copy(alpha = 0.76f),
                            MaterialTheme.shapes.medium
                        )
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }

        if (gestureLocked && !activity.pipMode) {
            Button(
                onClick = {
                    gestureLocked = false
                    controlsVisible = true
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
            ) {
                Text(stringResource(R.string.ui_unlock_gestures))
            }
        }

        playerError?.let { error ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        Color.Black.copy(alpha = 0.88f),
                        MaterialTheme.shapes.medium
                    )
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.ui_playback_error), color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    error,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Button(
                    onClick = {
                        playerError = null
                        player.prepare()
                        player.play()
                    }
                ) {
                    Text(stringResource(R.string.ui_retry))
                }
            }
        }

        if (controlsVisible && !activity.pipMode && !gestureLocked) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = if (isFullscreen) 390.dp else 590.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.97f)
                            )
                        )
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
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
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(stringResource(R.string.ui_open))
                    }

                    Spacer(Modifier.width(5.dp))

                    OutlinedButton(onClick = { folderPicker.launch(null) }) {
                        Text(stringResource(R.string.ui_folder))
                    }

                    Spacer(Modifier.width(5.dp))

                    RecentMenu(
                        videos = recentVideos,
                        onOpen = {
                            val uri = Uri.parse(it.uri)
                            requestOpen(
                                listOf(VideoEntry(uri, it.name))
                            )
                        }
                    )

                    Spacer(Modifier.weight(1f))

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        player.mediaItemCount > 0
                    ) {
                        TextButton(
                            onClick = {
                                val videoSize = player.videoSize
                                activity.enterPlayerPip(
                                    videoSize.width,
                                    videoSize.height
                                )
                            }
                        ) {
                            Text(stringResource(R.string.ui_pip))
                        }
                    }

                    TextButton(
                        onClick = {
                            isFullscreen = !isFullscreen
                            activity.setPlayerFullscreen(isFullscreen)
                            controlsVisible = true
                        }
                    ) {
                        Text(if (isFullscreen) "Exit full" else "Full")
                    }
                }

                Text(
                    text = fileName,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                if (fileInfo.isNotBlank()) {
                    Text(
                        fileInfo,
                        color = Color.White.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Slider(
                    value = currentMs.coerceAtMost(max(durationMs, 0L)).toFloat(),
                    onValueChange = {
                        if (durationMs > 0) player.seekTo(it.toLong())
                    },
                    valueRange = 0f..max(durationMs.toFloat(), 1f),
                    enabled = durationMs > 0L
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatTime(currentMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        stringResource(R.string.playback_time_saved, formatSeconds(skippedSeconds), formatSeconds(estimatedWatchSeconds)),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        if (durationMs > 0) {
                            "−" + formatTime((durationMs - currentMs).coerceAtLeast(0L))
                        } else {
                            "--:--"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { player.seekToPreviousMediaItem() },
                        enabled = player.hasPreviousMediaItem()
                    ) {
                        Text(stringResource(R.string.ui_prev))
                    }

                    IconButton(
                        onClick = {
                            seekBy(-doubleTapSeconds * 1000L)
                        },
                        enabled = player.mediaItemCount > 0
                    ) {
                        Icon(
                            Icons.Default.FastRewind,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                        },
                        enabled = player.mediaItemCount > 0
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play pause",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            seekBy(doubleTapSeconds * 1000L)
                        },
                        enabled = player.mediaItemCount > 0
                    ) {
                        Icon(
                            Icons.Default.FastForward,
                            contentDescription = "Forward",
                            tint = Color.White
                        )
                    }

                    TextButton(
                        onClick = { player.seekToNextMediaItem() },
                        enabled = player.hasNextMediaItem()
                    ) {
                        Text(stringResource(R.string.ui_next))
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.playback_speed_label, String.format("%.2f", playbackSpeed)),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(105.dp)
                    )
                    Slider(
                        value = playbackSpeed,
                        onValueChange = {
                            playbackSpeed =
                                ((it * 4).roundToInt() / 4f)
                                    .coerceIn(0.5f, 3f)
                            player.setPlaybackSpeed(playbackSpeed)
                        },
                        onValueChangeFinished = {
                            preferences.edit()
                                .putFloat("playback_speed", playbackSpeed)
                                .apply()
                            saveCurrentState()
                        },
                        valueRange = 0.5f..3f,
                        steps = 9,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.ui_skip_silence), color = Color.White)
                    Spacer(Modifier.width(7.dp))
                    Switch(
                        checked = skipSilence,
                        onCheckedChange = {
                            skipSilence = it
                            PlaybackRuntime.setSkipSilenceEnabled(it)
                            preferences.edit().putBoolean("skip_silence", it).apply()
                            saveCurrentState()
                        }
                    )
                    Spacer(Modifier.weight(1f))
                    PresetMenu(
                        current = SilencePreset.matching(
                            silenceThresholdDb,
                            minimumSilence,
                            edgePadding
                        ),
                        onPreset = {
                            silenceThresholdDb = it.thresholdDb
                            minimumSilence = it.minimumSilence
                            edgePadding = it.edgePadding
                            applySilenceSettings()
                            gestureText = it.label
                        }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Brightness6,
                        contentDescription = "Brightness",
                        tint = Color.White
                    )
                    Slider(
                        value = brightness,
                        onValueChange =(::setBrightness),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = "Volume",
                        tint = Color.White
                    )
                    Slider(
                        value = volume,
                        onValueChange =(::setVolume),
                        modifier = Modifier.weight(1f)
                    )
                }

                TextButton(
                    onClick = { advancedExpanded = !advancedExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (advancedExpanded) "Hide advanced" else "Advanced")
                }

                if (advancedExpanded) {
                    HorizontalDivider()

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.silence_threshold_label, silenceThresholdDb.roundToInt()),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(125.dp)
                        )
                        Slider(
                            value = silenceThresholdDb,
                            onValueChange = { silenceThresholdDb = it },
                            onValueChangeFinished =(::applySilenceSettings),
                            valueRange = -60f..-20f,
                            steps = 39,
                            enabled = skipSilence,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.minimum_silence_label, String.format("%.2f", minimumSilence)),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(125.dp)
                        )
                        Slider(
                            value = minimumSilence,
                            onValueChange = {
                                minimumSilence =
                                    ((it * 20).roundToInt() / 20f)
                            },
                            onValueChangeFinished =(::applySilenceSettings),
                            valueRange = 0.2f..2f,
                            steps = 35,
                            enabled = skipSilence,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.edge_padding_label, (edgePadding * 1000).roundToInt()),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(125.dp)
                        )
                        Slider(
                            value = edgePadding,
                            onValueChange = {
                                edgePadding =
                                    ((it * 100).roundToInt() / 100f)
                            },
                            onValueChangeFinished =(::applySilenceSettings),
                            valueRange = 0.02f..0.20f,
                            steps = 17,
                            enabled = skipSilence,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AspectMenu(
                            aspectMode,
                            onSelect = {
                                aspectMode = it
                                preferences.edit()
                                    .putString("aspect_mode", it.name)
                                    .apply()
                            }
                        )
                        DoubleTapMenu(
                            doubleTapSeconds,
                            onSelect = {
                                doubleTapSeconds = it
                                preferences.edit()
                                    .putInt("double_tap_seconds", it)
                                    .apply()
                            }
                        )
                        ResumeModeMenu(
                            resumeMode,
                            onSelect = {
                                resumeMode = it
                                preferences.edit()
                                    .putString("resume_mode", it.name)
                                    .apply()
                            }
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.video_zoom_label, String.format("%.1f", videoZoom)),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(105.dp)
                        )
                        Slider(
                            value = videoZoom,
                            onValueChange = {
                                videoZoom = it.coerceIn(1f, 3f)
                            },
                            onValueChangeFinished = {
                                preferences.edit()
                                    .putFloat("video_zoom", videoZoom)
                                    .apply()
                            },
                            valueRange = 1f..3f,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.ui_audio_only), color = Color.White)
                        Switch(
                            checked = audioOnly,
                            onCheckedChange = { audioOnly = it }
                        )
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = {
                                gestureLocked = true
                                controlsVisible = false
                            }
                        ) {
                            Text(stringResource(R.string.ui_lock_gestures))
                        }
                    }

                    HorizontalDivider()
                    Text(
                        stringResource(R.string.ui_subtitles),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                subtitlePicker.launch(
                                    arrayOf(
                                        "text/*",
                                        "application/x-subrip",
                                        "application/ttml+xml",
                                        "application/octet-stream"
                                    )
                                )
                            },
                            enabled = activeUri != null
                        ) {
                            Text(
                                subtitleName?.let { "External: $it" }
                                    ?: "Import subtitle",
                                maxLines = 1
                            )
                        }

                        if (selectedSubtitleUri != null) {
                            TextButton(
                                onClick = {
                                    loadExternalSubtitle(null)
                                    activeUri?.let { uri ->
                                        preferences.edit()
                                            .remove(
                                                "${mediaKey(uri)}_subtitle"
                                            )
                                            .apply()
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.ui_remove))
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

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.subtitle_sync_label, if (subtitleOffsetMs >= 0) "+" else "", subtitleOffsetMs),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(125.dp)
                        )
                        Slider(
                            value = subtitleOffsetMs.toFloat(),
                            onValueChange = {
                                subtitleOffsetMs =
                                    (it / 50f).roundToInt() * 50L
                            },
                            onValueChangeFinished = {
                                preferences.edit()
                                    .putLong("subtitle_offset_ms", subtitleOffsetMs)
                                    .apply()
                            },
                            valueRange = -10_000f..10_000f,
                            steps = 399,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.subtitle_font_scale_label, String.format("%.1f", subtitleFontScale)),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(125.dp)
                        )
                        Slider(
                            value = subtitleFontScale,
                            onValueChange = {
                                subtitleFontScale = it.coerceIn(0.7f, 2f)
                            },
                            onValueChangeFinished = {
                                preferences.edit()
                                    .putFloat(
                                        "subtitle_font_scale",
                                        subtitleFontScale
                                    )
                                    .apply()
                            },
                            valueRange = 0.7f..2f,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SubtitlePositionMenu(
                            subtitlePosition,
                            onSelect = {
                                subtitlePosition = it
                                preferences.edit()
                                    .putString("subtitle_position", it.name)
                                    .apply()
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.subtitle_background_label, (subtitleBackgroundOpacity * 100).roundToInt()),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = subtitleBackgroundOpacity,
                            onValueChange = {
                                subtitleBackgroundOpacity = it
                            },
                            onValueChangeFinished = {
                                preferences.edit()
                                    .putFloat(
                                        "subtitle_background_opacity",
                                        subtitleBackgroundOpacity
                                    )
                                    .apply()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = subtitleSearch,
                            onValueChange = { subtitleSearch = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.ui_search_subtitle_text)) },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick =(::searchSubtitle),
                            enabled = externalSubtitleCues.isNotEmpty()
                        ) {
                            Text(stringResource(R.string.ui_next))
                        }
                    }

                    HorizontalDivider()
                    Text(
                        stringResource(R.string.ui_playback_tools),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                abStartMs = currentMs
                                if (abEndMs != null && abEndMs!! <= currentMs) {
                                    abEndMs = null
                                }
                            }
                        ) {
                            Text(
                                abStartMs?.let { "A ${formatTime(it)}" }
                                    ?: "Set A"
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                if (abStartMs != null && currentMs > abStartMs!!) {
                                    abEndMs = currentMs
                                }
                            },
                            enabled = abStartMs != null
                        ) {
                            Text(
                                abEndMs?.let { "B ${formatTime(it)}" }
                                    ?: "Set B"
                            )
                        }

                        TextButton(
                            onClick = {
                                abStartMs = null
                                abEndMs = null
                            },
                            enabled = abStartMs != null || abEndMs != null
                        ) {
                            Text(stringResource(R.string.ui_clear_a_b))
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick =(::addBookmark),
                            enabled = activeUri != null
                        ) {
                            Text(stringResource(R.string.ui_add_bookmark))
                        }
                        BookmarkMenu(
                            bookmarks,
                            onSelect = { player.seekTo(it.positionMs) },
                            onClear = {
                                activeUri?.let {
                                    bookmarks = emptyList()
                                    saveBookmarks(
                                        preferences,
                                        it,
                                        emptyList()
                                    )
                                }
                            }
                        )
                    }

                    ChapterMenu(
                        PlaybackRuntime.chapters,
                        onSelect = { player.seekTo(it.startMs) }
                    )

                    SleepMenu(
                        active = sleepDeadline != null || sleepAtEnd,
                        onMinutes = { minutes ->
                            sleepAtEnd = false
                            sleepDeadline =
                                SystemClock.elapsedRealtime() +
                                    minutes * 60_000L
                        },
                        onEnd = {
                            sleepDeadline = null
                            sleepAtEnd = true
                        },
                        onCancel = {
                            sleepDeadline = null
                            sleepAtEnd = false
                        }
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                recentVideos = emptyList()
                                clearPlaybackHistory(preferences)
                                gestureText = "Playback history cleared"
                            }
                        ) {
                            Text(stringResource(R.string.ui_clear_history))
                        }

                        TextButton(
                            onClick = {
                                silenceThresholdDb = -42f
                                minimumSilence = 0.45f
                                edgePadding = 0.08f
                                playbackSpeed = 1f
                                doubleTapSeconds = 10
                                skipSilence = true
                                videoZoom = 1f
                                aspectMode = AspectMode.FIT
                                subtitleOffsetMs = 0L
                                subtitleFontScale = 1f
                                subtitleBackgroundOpacity = 0.72f
                                subtitlePosition = SubtitlePosition.BOTTOM
                                audioOnly = false
                                preferences.edit()
                                    .putFloat("silence_threshold_db", -42f)
                                    .putFloat("minimum_silence", 0.45f)
                                    .putFloat("edge_padding", 0.08f)
                                    .putFloat("playback_speed", 1f)
                                    .putInt("double_tap_seconds", 10)
                                    .putBoolean("skip_silence", true)
                                    .putFloat("video_zoom", 1f)
                                    .putString("aspect_mode", AspectMode.FIT.name)
                                    .putLong("subtitle_offset_ms", 0L)
                                    .putFloat("subtitle_font_scale", 1f)
                                    .putFloat(
                                        "subtitle_background_opacity",
                                        0.72f
                                    )
                                    .putString(
                                        "subtitle_position",
                                        SubtitlePosition.BOTTOM.name
                                    )
                                    .putBoolean("audio_only", false)
                                    .apply()
                                player.setPlaybackSpeed(1f)
                                PlaybackRuntime.setSkipSilenceEnabled(true)
                                applySilenceSettings()
                                gestureText = "Defaults restored"
                            }
                        ) {
                            Text(stringResource(R.string.ui_reset_settings))
                        }
                    }
                }

                Text(
                    "Double-tap ±${doubleTapSeconds}s · horizontal swipe seeks · left/right vertical swipe controls brightness/volume · pinch zoom",
                    color = Color.White.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }

    pendingResume?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingResume = null },
            title = { Text(stringResource(R.string.ui_resume_playback)) },
            text = {
                Text(stringResource(R.string.continue_from_time, formatTime(request.positionMs)))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingResume = null
                        commitOpen(
                            request.entries,
                            request.startIndex,
                            request.positionMs
                        )
                    }
                ) {
                    Text(stringResource(R.string.ui_resume))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingResume = null
                        commitOpen(
                            request.entries,
                            request.startIndex,
                            0L
                        )
                    }
                ) {
                    Text(stringResource(R.string.ui_start_over))
                }
            }
        )
    }
}

@Composable
private fun RecentMenu(
    videos: List<RecentVideo>,
    onOpen: (RecentVideo) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = videos.isNotEmpty()
        ) {
            Text(stringResource(R.string.ui_recent))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            videos.forEach {
                DropdownMenuItem(
                    text = { Text(it.name, maxLines = 1) },
                    onClick = {
                        expanded = false
                        onOpen(it)
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
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(current?.label ?: "Custom")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            SilencePreset.entries.forEach {
                DropdownMenuItem(
                    text = { Text(localizePlayerLabel(it.label)) },
                    onClick = {
                        expanded = false
                        onPreset(it)
                    }
                )
            }
        }
    }
}

@Composable
private fun AspectMenu(
    current: AspectMode,
    onSelect: (AspectMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(localizePlayerLabel(current.label))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            AspectMode.entries.forEach {
                DropdownMenuItem(
                    text = { Text(localizePlayerLabel(it.label)) },
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
private fun DoubleTapMenu(
    seconds: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("Tap ${seconds}s")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            listOf(5, 10, 15, 30).forEach {
                DropdownMenuItem(
                    text = { Text("$it seconds") },
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
private fun ResumeModeMenu(
    current: ResumeMode,
    onSelect: (ResumeMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("Resume: ${current.label}")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            ResumeMode.entries.forEach {
                DropdownMenuItem(
                    text = { Text(localizePlayerLabel(it.label)) },
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
private fun SubtitlePositionMenu(
    current: SubtitlePosition,
    onSelect: (SubtitlePosition) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("Position: ${current.label}")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            SubtitlePosition.entries.forEach {
                DropdownMenuItem(
                    text = { Text(localizePlayerLabel(it.label)) },
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
private fun BookmarkMenu(
    bookmarks: List<BookmarkPoint>,
    onSelect: (BookmarkPoint) -> Unit,
    onClear: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = bookmarks.isNotEmpty()
        ) {
            Text(stringResource(R.string.bookmark_count, bookmarks.size))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            bookmarks.forEach {
                DropdownMenuItem(
                    text = { Text(localizePlayerLabel(it.label)) },
                    onClick = {
                        expanded = false
                        onSelect(it)
                    }
                )
            }
            if (bookmarks.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ui_clear_bookmarks)) },
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
private fun ChapterMenu(
    chapters: List<ChapterInfo>,
    onSelect: (ChapterInfo) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = chapters.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (chapters.isEmpty()) {
                    "Chapters: none detected"
                } else {
                    "Chapters (${chapters.size})"
                }
            )
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            chapters.forEach {
                DropdownMenuItem(
                    text = {
                        Text("${formatTime(it.startMs)} · ${it.title}")
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
private fun SleepMenu(
    active: Boolean,
    onMinutes: (Long) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (active) "Sleep timer: active" else "Sleep timer")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            listOf(15L, 30L, 45L, 60L).forEach {
                DropdownMenuItem(
                    text = { Text("$it minutes") },
                    onClick = {
                        expanded = false
                        onMinutes(it)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ui_at_end_of_video)) },
                onClick = {
                    expanded = false
                    onEnd()
                }
            )
            if (active) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ui_cancel_timer)) },
                    onClick = {
                        expanded = false
                        onCancel()
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
    val groups = tracks?.groups?.filter { it.type == trackType }.orEmpty()
    var expanded by remember { mutableStateOf(false) }
    val selectedName = groups.firstNotNullOfOrNull { group ->
        (0 until group.length)
            .firstOrNull { group.isTrackSelected(it) }
            ?.let { trackLabel(group, it) }
    } ?: if (includeOff) "Off/Auto" else "Auto"

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = groups.isNotEmpty() || includeOff,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("$title: $selectedName", maxLines = 1)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (includeOff) "Off" else "Auto") },
                onClick = {
                    expanded = false
                    val builder = player.trackSelectionParameters
                        .buildUpon()
                        .clearOverridesOfType(trackType)
                        .setTrackTypeDisabled(trackType, includeOff)
                    player.trackSelectionParameters = builder.build()
                }
            )

            groups.forEach { group ->
                for (index in 0 until group.length) {
                    DropdownMenuItem(
                        text = { Text(trackLabel(group, index)) },
                        onClick = {
                            expanded = false
                            player.trackSelectionParameters =
                                player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(trackType, false)
                                    .setOverrideForType(
                                        TrackSelectionOverride(
                                            group.mediaTrackGroup,
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

private fun trackLabel(group: Tracks.Group, index: Int): String {
    val format = group.getTrackFormat(index)
    val base =
        format.label ?: format.language?.uppercase() ?: "Track ${index + 1}"
    return if (group.type == C.TRACK_TYPE_AUDIO) {
        val channels =
            format.channelCount.takeIf { it > 0 }?.let { " · ${it}ch" }.orEmpty()
        "$base$channels"
    } else {
        base
    }
}

private fun mediaKey(uri: Uri): String {
    return "media_" + uri.toString().hashCode().toUInt().toString(16)
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)
            } else {
                null
            }
        }
    }.getOrNull()
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "Video"
}

private fun queryFileSize(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                formatBytes(cursor.getLong(index))
            } else {
                ""
            }
        } ?: ""
    }.getOrDefault("")
}

private fun collectVideoDocuments(
    context: Context,
    treeUri: Uri
): List<VideoEntry> {
    val resolver = context.contentResolver
    val results = mutableListOf<VideoEntry>()
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)

    fun walk(documentId: String) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            documentId
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        runCatching {
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                )
                val nameColumn = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                )
                val mimeColumn = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )

                while (cursor.moveToNext()) {
                    val id = cursor.getString(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Video"
                    val mime = cursor.getString(mimeColumn).orEmpty()

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(id)
                    } else if (
                        mime.startsWith("video/") ||
                        name.substringAfterLast('.', "")
                            .lowercase() in setOf(
                            "mkv",
                            "webm",
                            "mp4",
                            "m4v",
                            "mov",
                            "avi",
                            "ts",
                            "mts",
                            "m2ts"
                        )
                    ) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            id
                        )
                        results += VideoEntry(uri, name)
                    }
                }
            }
        }
    }

    walk(rootId)
    return results.sortedBy { it.name.lowercase() }
}

private fun takePersistableReadPermission(activity: Activity, uri: Uri) {
    runCatching {
        activity.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
}

private fun takePersistableTreePermission(activity: Activity, uri: Uri) {
    runCatching {
        activity.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }
}

private fun loadRecentVideos(
    preferences: android.content.SharedPreferences
): List<RecentVideo> {
    val raw = preferences.getString("recent_videos", null) ?: return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val uri = item.optString("uri")
                if (uri.isBlank()) continue
                add(
                    RecentVideo(
                        uri,
                        item.optString("name", "Video")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun saveRecentVideos(
    preferences: android.content.SharedPreferences,
    videos: List<RecentVideo>
) {
    val array = JSONArray()
    videos.take(12).forEach {
        array.put(
            JSONObject()
                .put("uri", it.uri)
                .put("name", it.name)
        )
    }
    preferences.edit().putString("recent_videos", array.toString()).apply()
}

private fun loadBookmarks(
    preferences: android.content.SharedPreferences,
    uri: Uri
): List<BookmarkPoint> {
    val raw = preferences.getString("${mediaKey(uri)}_bookmarks", null)
        ?: return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    BookmarkPoint(
                        positionMs = item.getLong("position"),
                        label = item.optString(
                            "label",
                            formatTime(item.getLong("position"))
                        )
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun saveBookmarks(
    preferences: android.content.SharedPreferences,
    uri: Uri,
    bookmarks: List<BookmarkPoint>
) {
    val array = JSONArray()
    bookmarks.forEach {
        array.put(
            JSONObject()
                .put("position", it.positionMs)
                .put("label", it.label)
        )
    }
    preferences.edit()
        .putString("${mediaKey(uri)}_bookmarks", array.toString())
        .apply()
}

private fun clearPlaybackHistory(
    preferences: android.content.SharedPreferences
) {
    val editor = preferences.edit().remove("recent_videos")
    preferences.all.keys
        .filter { it.startsWith("media_") && it.endsWith("_position") }
        .forEach(editor::remove)
    editor.apply()
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format("%.2f GB", mb / 1024.0)
    } else {
        String.format("%.1f MB", mb)
    }
}

private fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000L
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

private fun formatSeconds(seconds: Float): String {
    return formatTime((seconds.coerceAtLeast(0f) * 1000f).toLong())
}
