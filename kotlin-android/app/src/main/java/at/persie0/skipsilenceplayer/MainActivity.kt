@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

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
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureInPicture
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
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

private val PlayerAccent = Color(0xFF3FD8C7)
private val PlayerAccentDark = Color(0xFF04211C)
private val PlayerSheetColor = Color(0xFF171B22)

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
        // Video and Library both use a dark canvas. The default automatic
        // edge-to-edge style can choose *dark icons* on that dark surface when
        // the device is in light mode, making the clock and system icons
        // nearly invisible. Keep light icons regardless of device theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        // The player always uses a dark canvas, regardless of the device theme.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
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
            MaterialTheme(
                colorScheme = darkColorScheme(primary = PlayerAccent)
            ) {
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
    val name: String,
    val subtitleUri: Uri? = null
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
    CROP("Crop", AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
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
    var silencePlaybackMode by remember {
        mutableStateOf(
            SilencePlaybackMode.fromPreference(
                preferences.getString("silence_playback_mode", null)
            )
        )
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
    var adaptiveSilence by remember {
        mutableStateOf(preferences.getBoolean("adaptive_silence", true))
    }
    var playbackSpeed by remember {
        mutableFloatStateOf(preferences.getFloat("playback_speed", 1f))
    }
    var quietAcceleration by remember {
        mutableFloatStateOf(preferences.getFloat("quiet_acceleration", 4f))
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
    var lastKnownPositionMs by remember { mutableLongStateOf(0L) }
    var lastKnownDurationMs by remember { mutableLongStateOf(0L) }
    var dismissedError by remember { mutableStateOf<String?>(null) }
    var subtitleLoadRevision by remember { mutableIntStateOf(0) }

    var recentVideos by remember {
        mutableStateOf(loadRecentVideos(preferences))
    }
    var recentFolders by remember {
        mutableStateOf(loadRecentVideos(preferences, "recent_folders"))
    }
    var currentQueueEntries by remember {
        mutableStateOf<List<VideoEntry>>(emptyList())
    }
    val silencePreviewRanges = PlaybackRuntime.silenceRanges
    // Decoding publishes a new set of ranges as it progresses. Calculate
    // their total once per analysis update, not on every 200 ms UI tick.
    val analyzedQuietSeconds = remember(silencePreviewRanges) {
        silencePreviewRanges.sumOf { it.durationMs }.toFloat() / 1000f
    }
    // LaunchedEffect(player) is long lived. A plain captured local here would
    // remain the *initial* empty analysis map after recomposition.
    val latestAnalyzedQuietSeconds by rememberUpdatedState(analyzedQuietSeconds)

    var controlsVisible by remember { mutableStateOf(true) }
    var showLibrary by rememberSaveable { mutableStateOf(false) }
    var libraryError by remember { mutableStateOf<String?>(null) }
    var rootHeightPx by remember { mutableIntStateOf(0) }
    var bottomControlsHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val compactHeight = configuration.screenHeightDp < 460 ||
        configuration.fontScale >= 1.4f
    val skipSilenceContentDescription = stringResource(R.string.ui_skip_silence)
    var showAdvancedSubtitle by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var gestureLocked by remember { mutableStateOf(false) }
    var gestureText by remember { mutableStateOf<String?>(null) }
    var libraryError by remember { mutableStateOf<String?>(null) }

    var overflowOpen by remember { mutableStateOf(false) }
    var showSpeedSheet by rememberSaveable { mutableStateOf(false) }
    var showSilenceSheet by rememberSaveable { mutableStateOf(false) }
    var showSubtitleSheet by rememberSaveable { mutableStateOf(false) }
    var showAudioSheet by rememberSaveable { mutableStateOf(false) }
    var showToolsSheet by rememberSaveable { mutableStateOf(false) }
    var showMarkersSheet by rememberSaveable { mutableStateOf(false) }
    val localConfiguration = LocalConfiguration.current
    val compactLandscape = localConfiguration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        localConfiguration.screenHeightDp < 500
    val narrowLandscape = compactLandscape &&
        (localConfiguration.screenWidthDp < 620 || localConfiguration.fontScale >= 1.3f)
    val compactPortraitActions =
        localConfiguration.screenWidthDp < 380 || localConfiguration.fontScale >= 1.3f
    var showPlaylistSheet by rememberSaveable { mutableStateOf(false) }
    var showRecentSheet by rememberSaveable { mutableStateOf(false) }
    var showPreferencesSheet by rememberSaveable { mutableStateOf(false) }
    var showHelpSheet by remember { mutableStateOf(false) }
    var showAdvancedSilence by remember { mutableStateOf(false) }
    var pendingDestructiveAction by remember { mutableStateOf<String?>(null) }

    // A media overlay is a focused operation. Auto-hide must not run behind
    // Settings, modal sheets or confirmation prompts, then should restart
    // when the user returns to playback.
    val playbackOverlayOpen =
        showLibrary || overflowOpen || showSpeedSheet || showSilenceSheet ||
            showSubtitleSheet || showAudioSheet || showToolsSheet ||
            showMarkersSheet || showPlaylistSheet || showRecentSheet ||
            showPreferencesSheet || pendingResume != null ||
            pendingDestructiveAction != null

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
    var selectedSleepMinutes by remember { mutableStateOf<Long?>(null) }
    var sleepAtEnd by remember { mutableStateOf(false) }

    var skippedSeconds by remember { mutableFloatStateOf(0f) }
    var estimatedSkippedSeconds by remember { mutableFloatStateOf(0f) }
    var estimatedWatchSeconds by remember { mutableFloatStateOf(0f) }

    val latestBrightness = rememberUpdatedState(brightness)
    val latestVolume = rememberUpdatedState(volume)
    val latestDoubleTap = rememberUpdatedState(doubleTapSeconds)
    val latestGestureLocked = rememberUpdatedState(gestureLocked)
    val rootFocusRequester = remember { FocusRequester() }

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

    fun persistQueue(entries: List<VideoEntry>, currentUri: Uri?) {
        if (entries.isEmpty()) return
        val encoded = QueueCodec.encode(
            entries.map {
                QueueRecord(
                    uri = it.uri.toString(),
                    name = it.name,
                    subtitleUri = it.subtitleUri?.toString()
                )
            },
            currentUri?.toString()
        )
        preferences.edit().putString("last_queue", encoded).apply()
    }

    fun saveCurrentState() {
        val uri = activeUri ?: return
        val key = mediaKey(uri)
        // During an automatic playlist transition the controller is already
        // reporting the NEXT item's position. Never overwrite the previous
        // video's resume position with that value.
        val position =
            if (player.currentMediaItem?.mediaId == activeMediaId) {
                player.currentPosition.coerceAtLeast(0L)
            } else {
                lastKnownPositionMs
            }
        val savedPosition = if (
            lastKnownDurationMs > 0 &&
            position >= lastKnownDurationMs - 1_500L
        ) 0L else position
        preferences.edit()
            .putLong("${key}_position", savedPosition)
            .putFloat("${key}_speed", playbackSpeed)
            .putFloat("${key}_threshold", silenceThresholdDb)
            .putFloat("${key}_minimum", minimumSilence)
            .putFloat("${key}_padding", edgePadding)
            .putBoolean("${key}_skip", skipSilence)
            .putBoolean("${key}_adaptive", adaptiveSilence)
            .putString("${key}_subtitle", selectedSubtitleUri?.toString())
            .apply()
        if (currentQueueEntries.isNotEmpty()) {
            persistQueue(currentQueueEntries, uri)
        }
    }

    fun loadExternalSubtitle(uri: Uri?) {
        subtitleLoadRevision++
        val revision = subtitleLoadRevision
        selectedSubtitleUri = uri
        subtitleName = uri?.let { queryDisplayName(context, it) }
        externalSubtitleCues = emptyList()
        externalSubtitleText = ""

        if (uri == null) {
            externalSubtitleCues = emptyList()
            externalSubtitleText = ""
            return
        }

        scope.launch {
            val cues = withContext(Dispatchers.IO) {
                runCatching { SubtitleParser.parse(context, uri) }
                    .getOrDefault(emptyList())
            }
            if (revision != subtitleLoadRevision || selectedSubtitleUri != uri) {
                return@launch
            }
            externalSubtitleCues = cues
            gestureText = if (cues.isEmpty()) {
                "No subtitle cues found or subtitle cannot be read"
            } else {
                "${cues.size} subtitle cues loaded"
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
        adaptiveSilence = preferences.getBoolean(
            "${key}_adaptive",
            preferences.getBoolean("adaptive_silence", true)
        )
        playbackSpeed = preferences.getFloat(
            "${key}_speed",
            preferences.getFloat("playback_speed", 1f)
        )
        skipSilence = preferences.getBoolean(
            "${key}_skip",
            preferences.getBoolean("skip_silence", true)
        )

        PlaybackRuntime.reconfigureSilence(
            SilenceConfig(newThreshold, newMinimum, newPadding, adaptiveSilence)
        )
        PlaybackRuntime.setBasePlaybackSpeed(playbackSpeed)
        PlaybackRuntime.setQuietSpeedMultiplier(quietAcceleration)
        PlaybackRuntime.setSkipSilenceEnabled(skipSilence)

        val subtitle = preferences.getString("${key}_subtitle", null)
            ?.let(Uri::parse)
        loadExternalSubtitle(subtitle)
        bookmarks = loadBookmarks(preferences, uri)
    }

    fun commitOpen(
        entries: List<VideoEntry>,
        startIndex: Int,
        positionMs: Long,
        autoPlay: Boolean = true
    ) {
        if (entries.isEmpty()) return
        saveCurrentState()
        showLibrary = false
        libraryError = null

        val index = startIndex.coerceIn(0, entries.lastIndex)
        val selected = entries[index]
        fileName = selected.name
        currentQueueEntries = entries
        abStartMs = null
        abEndMs = null
        dismissedError = null
        applyVideoPreferences(selected.uri)
        if (selectedSubtitleUri == null && selected.subtitleUri != null) {
            loadExternalSubtitle(selected.subtitleUri)
        }

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
        if (autoPlay) {
            player.play()
        } else {
            player.pause()
        }
        activeMediaId = selected.uri.toString()
        activeUri = selected.uri
        lastKnownPositionMs = positionMs.coerceAtLeast(0L)
        lastKnownDurationMs = 0L
        persistQueue(entries, selected.uri)
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
            .putBoolean("adaptive_silence", adaptiveSilence)
            .apply()
        saveCurrentState()
        PlaybackRuntime.reconfigureSilence(
            SilenceConfig(
                silenceThresholdDb,
                minimumSilence,
                edgePadding,
                adaptiveSilence
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

    LaunchedEffect(player) {
        val restored = QueueCodec.decode(
            preferences.getString("last_queue", null)
        )
        if (restored != null) {
            val available = restored.entries.mapNotNull { record ->
                val uri = Uri.parse(record.uri)
                if (canReadUri(context, uri)) {
                    VideoEntry(
                        uri = uri,
                        name = record.name,
                        subtitleUri = record.subtitleUri?.let(Uri::parse)
                    )
                } else {
                    null
                }
            }
            currentQueueEntries = available

            if (
                available.isNotEmpty() &&
                player.mediaItemCount == 0 &&
                activity.incomingUri == null &&
                pendingResume == null
            ) {
                val restoredIndex = available.indexOfFirst {
                    it.uri.toString() == restored.currentUri
                }.let { if (it >= 0) it else 0 }
                val restoredUri = available[restoredIndex].uri
                val position = preferences.getLong(
                    "${mediaKey(restoredUri)}_position",
                    0L
                )
                commitOpen(
                    entries = available,
                    startIndex = restoredIndex,
                    positionMs = position,
                    autoPlay = false
                )
            }
        }
    }

    LaunchedEffect(abEndMs) {
        PlaybackRuntime.setRepeatEndMs(abEndMs)
    }

    LaunchedEffect(player, PlaybackRuntime.generation) {
        PlaybackRuntime.setSkipSilenceEnabled(skipSilence)
        PlaybackRuntime.setSilencePlaybackMode(silencePlaybackMode)
        PlaybackRuntime.setBasePlaybackSpeed(playbackSpeed)
        PlaybackRuntime.setQuietSpeedMultiplier(quietAcceleration)
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
        var lastFileSizeUri: Uri? = null
        var cachedFileSize = ""
        var lastSilenceRanges: List<SilenceRangeMs>? = null
        var cachedAnalyzedSkippedSeconds = 0f
        while (isActive) {
            currentMs = player.currentPosition.coerceAtLeast(0L)
            val rawDuration = player.duration
            durationMs =
                if (rawDuration != C.TIME_UNSET && rawDuration > 0) rawDuration else 0L
            isPlaying = player.isPlaying
            currentTracks = player.currentTracks
            val diagnostics = buildMediaDiagnostics(currentTracks)
            val candidateError = player.playerError?.let {
                buildString {
                    append("${it.errorCodeName}: ${it.cause?.localizedMessage ?: it.localizedMessage ?: "Playback failed"}")
                    diagnostics.unsupportedSummary?.let { warning ->
                        append(" · ")
                        append(warning)
                    }
                }
            } ?: diagnostics.unsupportedSummary
            playerError = candidateError?.takeUnless { it == dismissedError }

            val currentItem = player.currentMediaItem
            val uri = currentItem?.localConfiguration?.uri
            val mediaId = currentItem?.mediaId.orEmpty()
            if (uri != null && mediaId != activeMediaId) {
                saveCurrentState()
                activeMediaId = mediaId
                activeUri = uri
                abStartMs = null
                abEndMs = null
                dismissedError = null
                fileName = currentItem.mediaMetadata.title?.toString()
                    ?: queryDisplayName(context, uri)
                fileInfo = ""
                applyVideoPreferences(uri)
                lastKnownPositionMs = currentMs

                if (selectedSubtitleUri == null) {
                    val automaticSubtitle = currentQueueEntries
                        .firstOrNull { it.uri == uri }
                        ?.subtitleUri
                    if (automaticSubtitle != null) {
                        loadExternalSubtitle(automaticSubtitle)
                    }
                }
                if (currentQueueEntries.isNotEmpty()) {
                    persistQueue(currentQueueEntries, uri)
                }

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

            val sizeUri = activeUri
            if (sizeUri != lastFileSizeUri) {
                lastFileSizeUri = sizeUri
                cachedFileSize = if (sizeUri != null) {
                    withContext(Dispatchers.IO) { queryFileSize(context, sizeUri) }
                } else {
                    ""
                }
            }
            fileInfo = listOf(
                cachedFileSize,
                if (durationMs > 0) formatTime(durationMs) else "",
                diagnostics.summary
            ).filter { it.isNotBlank() }.joinToString(" · ")

            skippedSeconds = PlaybackRuntime.skippedSeconds()
            val analyzedSkippedSeconds = latestAnalyzedQuietSeconds
            estimatedSkippedSeconds = when {
                !skipSilence -> 0f
                silencePlaybackMode == SilencePlaybackMode.SMOOTH -> {
                    // Smooth saves only the portion played faster than normal,
                    // rather than implying that all detected quiet time was cut.
                    (analyzedSkippedSeconds *
                        (1f - 1f / quietAcceleration.coerceIn(2f, 8f)))
                        .coerceAtLeast(skippedSeconds)
                }
                analyzedSkippedSeconds > 0f -> analyzedSkippedSeconds
                durationMs > 0 && currentMs > 30_000L -> {
                    val predicted =
                        skippedSeconds * (durationMs.toFloat() / currentMs.toFloat())
                    predicted.coerceIn(skippedSeconds, durationMs / 1000f)
                }
                else -> skippedSeconds
            }
            estimatedWatchSeconds =
                ((durationMs / 1000f - estimatedSkippedSeconds)
                    .coerceAtLeast(0f) / playbackSpeed.coerceAtLeast(0.5f))

            val subtitleClock = currentMs - subtitleOffsetMs
            externalSubtitleText = activeSubtitleCue(
                externalSubtitleCues,
                subtitleClock
            )?.text.orEmpty()

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
                selectedSleepMinutes = null
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
            lastKnownPositionMs = currentMs
            lastKnownDurationMs = durationMs
            delay(200)
        }
    }

    LaunchedEffect(gestureText) {
        if (gestureText != null) {
            delay(900)
            gestureText = null
        }
    }

    LaunchedEffect(playbackOverlayOpen) {
        if (playbackOverlayOpen && player.mediaItemCount > 0) {
            controlsVisible = true
        }
    }

    LaunchedEffect(
        isPlaying, controlsVisible, isFullscreen, activity.pipMode,
        gestureLocked, playbackOverlayOpen
    ) {
        if (isPlaying && controlsVisible && !activity.pipMode &&
            !gestureLocked && !playbackOverlayOpen
        ) {
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
            val folderName = runCatching {
                DocumentsContract.getTreeDocumentId(treeUri)
                    .substringAfterLast(':')
                    .ifBlank { "Folder" }
            }.getOrDefault("Folder")
            val folder = RecentVideo(treeUri.toString(), folderName)
            recentFolders = (listOf(folder) + recentFolders.filterNot {
                it.uri == folder.uri
            }).take(6)
            saveRecentVideos(preferences, recentFolders, "recent_folders")
            scope.launch {
                val entries = withContext(Dispatchers.IO) {
                    collectVideoDocuments(context, treeUri)
                }
                if (entries.isEmpty()) {
                    libraryError = "No videos found in the selected folder"
                } else {
                    libraryError = null
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

    fun openVideos() {
        videoPicker.launch(
            arrayOf(
                "video/*",
                "video/x-matroska",
                "video/webm",
                "application/octet-stream"
            )
        )
    }

    fun openFolder() {
        folderPicker.launch(null)
    }

    fun reopenFolder(folder: RecentVideo) {
        scope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching {
                    collectVideoDocuments(context, Uri.parse(folder.uri))
                }.getOrDefault(emptyList())
            }
            if (entries.isEmpty()) {
                libraryError = "Could not reopen folder, or no videos were found"
            } else {
                libraryError = null
                requestOpen(entries)
            }
        }
    }

    fun openSubtitleFile() {
        subtitlePicker.launch(
            arrayOf(
                "text/*",
                "application/x-subrip",
                "application/ttml+xml",
                "application/octet-stream"
            )
        )
    }

    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        activity.setPlayerFullscreen(isFullscreen)
        controlsVisible = true
    }

    fun enterPip() {
        val videoSize = player.videoSize
        activity.enterPlayerPip(
            videoSize.width,
            videoSize.height
        )
    }

    fun cycleAspect() {
        val modes = AspectMode.entries
        aspectMode = modes[(aspectMode.ordinal + 1) % modes.size]
        preferences.edit()
            .putString("aspect_mode", aspectMode.name)
            .apply()
    }

    fun cycleDoubleTap() {
        val options = listOf(5, 10, 15, 30)
        val index = options.indexOf(doubleTapSeconds).coerceAtLeast(0)
        doubleTapSeconds = options[(index + 1) % options.size]
        preferences.edit()
            .putInt("double_tap_seconds", doubleTapSeconds)
            .apply()
    }

    fun cycleResumeMode() {
        val modes = ResumeMode.entries
        resumeMode = modes[(resumeMode.ordinal + 1) % modes.size]
        preferences.edit()
            .putString("resume_mode", resumeMode.name)
            .apply()
    }

    fun setSpeed(speed: Float) {
        playbackSpeed = speed
        PlaybackRuntime.setBasePlaybackSpeed(speed)
        preferences.edit()
            .putFloat("playback_speed", speed)
            .apply()
        saveCurrentState()
    }

    fun setSkipSilence(enabled: Boolean) {
        skipSilence = enabled
        PlaybackRuntime.setSkipSilenceEnabled(enabled)
        preferences.edit().putBoolean("skip_silence", enabled).apply()
        saveCurrentState()
    }

    fun setSilenceMode(mode: SilencePlaybackMode) {
        silencePlaybackMode = mode
        PlaybackRuntime.setSilencePlaybackMode(mode)
        preferences.edit().putString("silence_playback_mode", mode.name).apply()
    }

    fun clearHistory() {
        recentVideos = emptyList()
        recentFolders = emptyList()
        clearPlaybackHistory(preferences)
        preferences.edit().remove("recent_folders").apply()
        gestureText = "Playback history cleared"
    }

    fun resetSettings() {
        silenceThresholdDb = -42f
        minimumSilence = 0.45f
        edgePadding = 0.08f
        playbackSpeed = 1f
        quietAcceleration = 4f
        doubleTapSeconds = 10
        skipSilence = true
        silencePlaybackMode = SilencePlaybackMode.SMOOTH
        adaptiveSilence = true
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
            .putFloat("quiet_acceleration", 4f)
            .putInt("double_tap_seconds", 10)
            .putBoolean("skip_silence", true)
            .putString("silence_playback_mode", SilencePlaybackMode.SMOOTH.name)
            .putBoolean("adaptive_silence", true)
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
        PlaybackRuntime.setBasePlaybackSpeed(1f)
        PlaybackRuntime.setQuietSpeedMultiplier(4f)
        PlaybackRuntime.setSkipSilenceEnabled(true)
        PlaybackRuntime.setSilencePlaybackMode(SilencePlaybackMode.SMOOTH)
        applySilenceSettings()
        gestureText = "Defaults restored"
    }

    val pointerGestures =
        if (gestureLocked || showLibrary) {
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

    LaunchedEffect(rootFocusRequester) {
        rootFocusRequester.requestFocus()
    }

    BackHandler(enabled = showPreferencesSheet) {
        showPreferencesSheet = false
    }

    BackHandler(
        enabled = player.mediaItemCount > 0 && !showLibrary &&
            !showSpeedSheet && !showSilenceSheet && !showSubtitleSheet &&
            !showAudioSheet && !showToolsSheet && !showPlaylistSheet &&
            !showRecentSheet && !showPreferencesSheet && !showMarkersSheet &&
            pendingResume == null && pendingDestructiveAction == null
    ) {
        if (gestureLocked) {
            gestureLocked = false
            controlsVisible = true
        } else if (isFullscreen) {
            isFullscreen = false
            activity.setPlayerFullscreen(false)
        } else {
            player.pause()
            saveCurrentState()
            showLibrary = true
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { rootHeightPx = it.height }
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val anySheetOpen =
                    showSpeedSheet ||
                        showSilenceSheet ||
                        showSubtitleSheet ||
                        showAudioSheet ||
                        showToolsSheet ||
                        showPlaylistSheet ||
                        showRecentSheet ||
                        showPreferencesSheet ||
                        showMarkersSheet

                if (
                    anySheetOpen ||
                    gestureLocked ||
                    showLibrary ||
                    event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN ||
                    event.nativeKeyEvent.repeatCount > 0
                ) {
                    false
                } else {
                    when (event.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_SPACE,
                        android.view.KeyEvent.KEYCODE_K -> {
                            if (player.mediaItemCount > 0) {
                                if (player.isPlaying) player.pause() else player.play()
                                controlsVisible = true
                                true
                            } else {
                                false
                            }
                        }

                        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                        android.view.KeyEvent.KEYCODE_J -> {
                            seekBy(-doubleTapSeconds * 1000L)
                            true
                        }

                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                        android.view.KeyEvent.KEYCODE_L -> {
                            seekBy(doubleTapSeconds * 1000L)
                            true
                        }

                        android.view.KeyEvent.KEYCODE_F -> {
                            isFullscreen = !isFullscreen
                            activity.setPlayerFullscreen(isFullscreen)
                            true
                        }

                        android.view.KeyEvent.KEYCODE_M -> {
                            setVolume(if (volume > 0.01f) 0f else 0.5f)
                            true
                        }

                        else -> false
                    }
                }
            }
    ) {
        AndroidView(
            factory = { viewContext ->
                // A TextureView stays in the Compose view hierarchy when a
                // fullscreen Settings overlay and orientation transitions
                // occur. SurfaceView can lose its surface behind the overlay
                // while the MediaSession keeps audio playing.
                (android.view.LayoutInflater.from(viewContext).inflate(
                    R.layout.video_player_texture, null, false
                ) as PlayerView).apply {
                    resizeMode = aspectMode.resizeMode
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                }
            },
            update = { view ->
                // Do not reset an attached output surface for every overlay
                // recomposition; only rebind after the controller is replaced.
                if (view.player !== player) view.player = player
                view.resizeMode = aspectMode.resizeMode
                view.subtitleView?.apply {
                    setApplyEmbeddedFontSizes(false)
                    setFractionalTextSize(
                        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION *
                            subtitleFontScale
                    )
                }
            },
            onRelease = { view ->
                // A released AndroidView must not retain the service's
                // video output. Recreated views (for example after a
                // configuration/window transition) attach a fresh surface.
                view.player = null
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

        if ((player.mediaItemCount == 0 || showLibrary) && !activity.pipMode) {
            HomeScreen(
                recentVideos = recentVideos,
                recentFolders = recentFolders,
                onOpenRecentFolder = { reopenFolder(it) },
                libraryError = libraryError,
                currentVideoName = if (player.mediaItemCount > 0) fileName else null,
                currentPositionMs = currentMs,
                currentDurationMs = durationMs,
                queueCount = player.mediaItemCount,
                onShowQueue = { showPlaylistSheet = true },
                onSettings = { showPreferencesSheet = true },
                recentResumePositionMs = { recent ->
                    preferences.getLong(
                        "${mediaKey(Uri.parse(recent.uri))}_position", 0L
                    )
                },
                onContinue = {
                    showLibrary = false
                    controlsVisible = true
                    player.play()
                },
                onOpenVideo = { openVideos() },
                onOpenFolder = { openFolder() },
                onOpenRecent = { recent ->
                    requestOpen(
                        listOf(VideoEntry(Uri.parse(recent.uri), recent.name))
                    )
                },
                onSeeAllRecents = { showRecentSheet = true },
                onHelp = { showHelpSheet = true }
            )
        }

        if (audioOnly && player.mediaItemCount > 0 &&
            !activity.pipMode && !showLibrary) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(44.dp)
                )
                Text(
                    stringResource(R.string.ui_audio_only),
                    color = Color.White.copy(alpha = 0.78f),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (externalSubtitleText.isNotBlank() && !showLibrary) {
            val bottomReserved = if (
                subtitlePosition == SubtitlePosition.BOTTOM && controlsVisible &&
                !gestureLocked && bottomControlsHeightPx > 0
            ) {
                with(density) {
                    minOf(
                        bottomControlsHeightPx + 12.dp.roundToPx(),
                        (rootHeightPx * 0.55f).roundToInt()
                    ).coerceAtLeast(0).toDp()
                }
            } else {
                32.dp
            }
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
                        start = 24.dp,
                        end = 24.dp,
                        top = if (subtitlePosition == SubtitlePosition.TOP) 24.dp else 0.dp,
                        bottom = if (subtitlePosition == SubtitlePosition.BOTTOM) {
                            bottomReserved
                        } else 0.dp
                    )
                    .background(
                        Color.Black.copy(alpha = subtitleBackgroundOpacity),
                        MaterialTheme.shapes.small
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }

        gestureText?.let { message ->
            if (!activity.pipMode && !showLibrary) {
                Text(
                    text = message,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(
                            Color.Black.copy(alpha = 0.78f),
                            RoundedCornerShape(50)
                        )
                        .border(
                            1.dp,
                            PlayerAccent.copy(alpha = 0.55f),
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }

        if (gestureLocked && !activity.pipMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 14.dp)
                    .background(
                        Color.Black.copy(alpha = 0.72f),
                        RoundedCornerShape(50)
                    )
                    .border(
                        1.dp,
                        PlayerAccent.copy(alpha = 0.6f),
                        RoundedCornerShape(50)
                    )
                    .heightIn(min = 48.dp)
                    .clickable {
                        gestureLocked = false
                        controlsVisible = true
                    }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Outlined.LockOpen,
                    contentDescription = null,
                    tint = PlayerAccent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    stringResource(R.string.ui_unlock_gestures),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        playerError?.let { error ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 20.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .background(
                        PlayerSheetColor,
                        MaterialTheme.shapes.medium
                    )
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.ui_playback_error),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    error,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    stringResource(R.string.ui_error_recovery_hint),
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = {
                        dismissedError = error
                        playerError = null
                        openVideos()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ui_open_another_video))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = {
                        dismissedError = error
                        playerError = null
                    }) {
                        Text(stringResource(R.string.ui_dismiss))
                    }
                    OutlinedButton(onClick = {
                        dismissedError = null
                        playerError = null
                        player.prepare()
                        player.play()
                    }) {
                        Text(stringResource(R.string.ui_retry))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        dismissedError = error
                        playerError = null
                    }) {
                        Text(stringResource(R.string.ui_dismiss))
                    }
                }
            )
        }

        AnimatedVisibility(
            visible = player.mediaItemCount > 0 && controlsVisible &&
                !activity.pipMode && !gestureLocked && !showLibrary,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.82f),
                                Color.Transparent
                            )
                        )
                    )
                    .statusBarsPadding()
                    .padding(
                        start = 18.dp,
                        end = 14.dp,
                        top = 8.dp,
                        bottom = if (compactLandscape) 5.dp else 26.dp
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    playerIconButton(
                        icon = Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.ui_library)
                    ) {
                        player.pause()
                        saveCurrentState()
                        if (isFullscreen) {
                            isFullscreen = false
                            activity.setPlayerFullscreen(false)
                        }
                        showLibrary = true
                        controlsVisible = false
                    }

                    Spacer(Modifier.width(4.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            fileName,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            maxLines = 1
                        )
                        if (fileInfo.isNotBlank()) {
                            Text(
                                fileInfo,
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    playerIconButton(
                        icon = Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.ui_library)
                    ) {
                        player.pause()
                        saveCurrentState()
                        if (isFullscreen) {
                            isFullscreen = false
                            activity.setPlayerFullscreen(false)
                        }
                        showLibrary = true
                        controlsVisible = false
                    }

                    Spacer(Modifier.width(4.dp))

                    if (player.mediaItemCount > 1) {
                        playerIconButton(
                            icon = Icons.Outlined.PlaylistPlay,
                            contentDescription = stringResource(R.string.ui_up_next)
                        ) {
                            showPlaylistSheet = true
                        }
                        Spacer(Modifier.width(4.dp))
                    }

                    playerIconButton(
                        icon = if (isFullscreen) {
                            Icons.Outlined.FullscreenExit
                        } else {
                            Icons.Outlined.Fullscreen
                        },
                        contentDescription = stringResource(R.string.cd_fullscreen)
                    ) {
                        toggleFullscreen()
                    }

                    Spacer(Modifier.width(4.dp))

                    Box {
                        playerIconButton(
                            icon = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.cd_overflow)
                        ) {
                            overflowOpen = true
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_lock_gestures)) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Lock, contentDescription = null)
                                },
                                onClick = {
                                    overflowOpen = false
                                    gestureLocked = true
                                    controlsVisible = false
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(R.string.ui_open_video))
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.FolderOpen,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    overflowOpen = false
                                    openVideos()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_folder)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Folder,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    overflowOpen = false
                                    openFolder()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_recent)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.History,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    overflowOpen = false
                                    showRecentSheet = true
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_settings)) },
                                onClick = {
                                    overflowOpen = false
                                    showPreferencesSheet = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_player_guide)) },
                                onClick = {
                                    overflowOpen = false
                                    showHelpSheet = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_audio)) },
                                onClick = {
                                    overflowOpen = false
                                    showAudioSheet = true
                                }
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ui_pip)) },
                                    onClick = {
                                        overflowOpen = false
                                        enterPip()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = player.mediaItemCount > 0 && controlsVisible &&
                !activity.pipMode && !gestureLocked && !showLibrary,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { bottomControlsHeightPx = it.height }
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.92f)
                            )
                        )
                    )
                    .navigationBarsPadding()
                    .padding(top = if (compactLandscape) 5.dp else 26.dp,
                        bottom = if (compactLandscape) 4.dp else 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatTime(currentMs),
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (durationMs > 0) {
                            "−" + formatTime((durationMs - currentMs).coerceAtLeast(0L))
                        } else {
                            "--:--"
                        },
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }

                PlayerSeekBar(
                    positionMs = currentMs,
                    durationMs = durationMs,
                    bookmarks = bookmarks,
                    silenceRanges = silencePreviewRanges,
                    onSeek = { target -> player.seekTo(target) },
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                if (compactLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (!narrowLandscape) {
                            IconButton(
                                onClick = { seekBy(-doubleTapSeconds * 1000L) }
                            ) {
                                Icon(
                                    Icons.Filled.FastRewind,
                                    contentDescription = stringResource(R.string.cd_seek_back),
                                    tint = Color.White
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                if (player.isPlaying) player.pause() else player.play()
                            }
                        ) {
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.cd_play_pause),
                                tint = PlayerAccent
                            )
                        }
                        if (!narrowLandscape) {
                            IconButton(
                                onClick = { seekBy(doubleTapSeconds * 1000L) }
                            ) {
                                Icon(
                                    Icons.Filled.FastForward,
                                    contentDescription = stringResource(R.string.cd_seek_forward),
                                    tint = Color.White
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clickable { showSilenceSheet = true },
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                stringResource(R.string.ui_skip_silence),
                                color = if (skipSilence) PlayerAccent else Color.White,
                                maxLines = 1,
                                fontSize = 13.sp
                            )
                            val status = PlaybackRuntime.analysisStatus
                            Text(
                                stringResource(
                                    when {
                                        !skipSilence -> R.string.ui_skipping_off
                                        status.startsWith("Analysis complete") ->
                                            R.string.ui_analysis_ready
                                        status.startsWith("Analysis capped") ||
                                            status.startsWith("Analysis stopped") ->
                                            R.string.ui_analysis_partial
                                        status.contains("no audio", ignoreCase = true) ->
                                            R.string.ui_no_audio_track
                                        status.contains("No local") ->
                                            R.string.ui_analysis_unavailable
                                        else -> R.string.ui_analyzing_audio
                                    }
                                ),
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = skipSilence,
                            onCheckedChange = { setSkipSilence(it) },
                            modifier = Modifier.semantics {
                                contentDescription = "Skip silence"
                            }
                        )
                        IconButton(onClick = { showToolsSheet = true }) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.ui_tools),
                                tint = Color.White
                            )
                        }
                    }
                } else {
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (player.mediaItemCount > 1) {
                        IconButton(
                            onClick = { player.seekToPreviousMediaItem() },
                            enabled = player.hasPreviousMediaItem()
                        ) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = stringResource(R.string.cd_prev),
                                tint = Color.White
                            )
                        }
                    }

                    IconButton(
                        onClick = { seekBy(-doubleTapSeconds * 1000L) },
                        enabled = player.mediaItemCount > 0
                    ) {
                        Icon(
                            Icons.Filled.FastRewind,
                            contentDescription = stringResource(
                                R.string.cd_seek_back_seconds, doubleTapSeconds
                            ),
                            tint = Color.White
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .background(PlayerAccent, CircleShape)
                            .clickable(enabled = player.mediaItemCount > 0) {
                                if (player.isPlaying) player.pause() else player.play()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (isPlaying) R.string.cd_pause else R.string.cd_play
                            ),
                            tint = PlayerAccentDark,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    IconButton(
                        onClick = { seekBy(doubleTapSeconds * 1000L) },
                        enabled = player.mediaItemCount > 0
                    ) {
                        Icon(
                            Icons.Filled.FastForward,
                            contentDescription = stringResource(
                                R.string.cd_seek_forward_seconds, doubleTapSeconds
                            ),
                            tint = Color.White
                        )
                    }

                    if (player.mediaItemCount > 1) {
                        IconButton(
                            onClick = { player.seekToNextMediaItem() },
                            enabled = player.hasNextMediaItem()
                        ) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = stringResource(R.string.cd_next),
                                tint = Color.White
                            )
                        }
                    }
                }

                if (!compactHeight) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, start = 14.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TextButton(
                        onClick = { showSilenceSheet = true },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                stringResource(R.string.ui_skip_silence),
                                fontSize = 13.sp,
                                maxLines = 1,
                                color = if (skipSilence) PlayerAccent else Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (!skipSilence) {
                                    stringResource(R.string.ui_off)
                                } else {
                                    stringResource(
                                        R.string.ui_silence_compact_saved,
                                        stringResource(
                                            if (silencePlaybackMode == SilencePlaybackMode.SMOOTH) {
                                                R.string.ui_silence_smooth
                                            } else {
                                                R.string.ui_silence_jump
                                            }
                                        ),
                                        formatSeconds(skippedSeconds)
                                    )
                                },
                                fontSize = 12.sp,
                                maxLines = 1,
                                color = Color.White.copy(alpha = 0.78f)
                            )
                        }
                    }
                    Switch(
                        checked = skipSilence,
                        onCheckedChange = { setSkipSilence(it) },
                        modifier = Modifier.semantics {
                            contentDescription = "Skip silence"
                        }
                    )
                    if (!compactPortraitActions) {
                        TextButton(onClick = { showSpeedSheet = true }) {
                            Text(
                                formatSpeedLabel(playbackSpeed),
                                fontSize = 13.sp,
                                color = Color.White
                            )
                        }
                        IconButton(onClick = { showSubtitleSheet = true }) {
                            Icon(
                                Icons.Outlined.Subtitles,
                                contentDescription = stringResource(R.string.ui_subtitles),
                                tint = Color.White
                            )
                        }
                    }
                    IconButton(onClick = { showToolsSheet = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.ui_tools),
                            tint = Color.White
                        )
                    }
                }
                }
            }
        }

        if (showHelpSheet) {
            ModalBottomSheet(
                onDismissRequest = { showHelpSheet = false },
                containerColor = PlayerSheetColor
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SheetHeader(stringResource(R.string.ui_player_guide)) {
                        showHelpSheet = false
                    }
                    Text(
                        stringResource(R.string.ui_guide_intro),
                        color = Color.White.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    SectionLabel(stringResource(R.string.ui_guide_touch))
                    Text(stringResource(R.string.ui_guide_seek),
                        color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.ui_guide_swipe),
                        color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.ui_guide_pinch_lock),
                        color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    SectionLabel(stringResource(R.string.ui_guide_silence_title))
                    Text(stringResource(R.string.ui_guide_silence),
                        color = Color.White.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.bodyMedium)
                    SectionLabel(stringResource(R.string.ui_guide_more_title))
                    Text(stringResource(R.string.ui_guide_more),
                        color = Color.White.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (showPreferencesSheet) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PlayerSheetColor)
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 28.dp)
                ) {
                    SheetHeader(stringResource(R.string.ui_settings)) {
                        showPreferencesSheet = false
                    }

                    SectionLabel(stringResource(R.string.ui_aspect))
                    ChoiceChips(
                        options = AspectMode.entries.toList(),
                        selected = { aspectMode == it },
                        label = { localizePlayerLabel(it.label) },
                        onSelect = {
                            aspectMode = it
                            preferences.edit().putString("aspect_mode", it.name).apply()
                        }
                    )

                    SectionLabel(stringResource(R.string.ui_double_tap_seek))
                    ChoiceChips(
                        options = listOf(5, 10, 15, 30),
                        selected = { doubleTapSeconds == it },
                        label = { "$it s" },
                        onSelect = {
                            doubleTapSeconds = it
                            preferences.edit().putInt("double_tap_seconds", it).apply()
                        }
                    )

                    SectionLabel(stringResource(R.string.ui_resume_behavior))
                    ChoiceChips(
                        options = ResumeMode.entries.toList(),
                        selected = { resumeMode == it },
                        label = { localizePlayerLabel(it.label) },
                        onSelect = {
                            resumeMode = it
                            preferences.edit().putString("resume_mode", it.name).apply()
                        }
                    )

                    SheetSwitchRow(
                        label = stringResource(R.string.ui_audio_only),
                        checked = audioOnly,
                        onCheckedChange = {
                            audioOnly = it
                            preferences.edit().putBoolean("audio_only", it).apply()
                        }
                    )

                    SectionLabel(stringResource(R.string.ui_data))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showPreferencesSheet = false
                                pendingDestructiveAction = "history"
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.ui_clear_history))
                        }
                        OutlinedButton(
                            onClick = {
                                showPreferencesSheet = false
                                pendingDestructiveAction = "settings"
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.ui_reset_settings))
                        }
                    }
                }
            }
        }

        if (showSpeedSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSpeedSheet = false },
                containerColor = PlayerSheetColor
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 28.dp)
                ) {
                    SheetHeader(stringResource(R.string.ui_playback_speed_title)) {
                        showSpeedSheet = false
                    }

                    Text(
                        stringResource(R.string.ui_playback_speed_hint),
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.82f),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    ChoiceChips(
                        options = listOf(
                            0.5f, 0.75f, 1f, 1.25f,
                            1.5f, 1.75f, 2f, 2.5f, 3f
                        ),
                        selected = { abs(playbackSpeed - it) < 0.01f },
                        label = { formatSpeedLabel(it) },
                        onSelect = { setSpeed(it) }
                    )

                    SheetSliderRow(
                        label = stringResource(R.string.ui_normal_playback_speed),
                        valueText = formatSpeedLabel(playbackSpeed),
                        value = playbackSpeed,
                        onValueChange = {
                            playbackSpeed =
                                ((it * 4).roundToInt() / 4f)
                                    .coerceIn(0.5f, 3f)
                            PlaybackRuntime.setBasePlaybackSpeed(playbackSpeed)
                        },
                        onValueChangeFinished = {
                            preferences.edit()
                                .putFloat("playback_speed", playbackSpeed)
                                .apply()
                            saveCurrentState()
                        },
                        valueRange = 0.5f..3f,
                        steps = 9
                    )
                }
            }
        }

        if (showSilenceSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSilenceSheet = false },
                containerColor = PlayerSheetColor
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 28.dp)
                ) {
                    SheetHeader(stringResource(R.string.ui_silence_skipping)) {
                        showSilenceSheet = false
                    }

                    SheetSwitchRow(
                        label = stringResource(R.string.ui_skip_silence),
                        checked = skipSilence,
                        onCheckedChange = { setSkipSilence(it) }
                    )

                    SectionLabel(stringResource(R.string.ui_silence_mode_title))
                    ChoiceChips(
                        options = SilencePlaybackMode.entries.toList(),
                        selected = { silencePlaybackMode == it },
                        label = { mode ->
                            stringResource(
                                if (mode == SilencePlaybackMode.SMOOTH) {
                                    R.string.ui_silence_smooth
                                } else {
                                    R.string.ui_silence_jump
                                }
                            )
                        },
                        onSelect = { setSilenceMode(it) }
                    )
                    Text(
                        stringResource(
                            if (silencePlaybackMode == SilencePlaybackMode.SMOOTH) {
                                R.string.ui_silence_smooth_explanation
                            } else {
                                R.string.ui_silence_jump_explanation
                            }
                        ),
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    if (silencePlaybackMode == SilencePlaybackMode.SMOOTH) {
                        SheetSliderRow(
                            label = stringResource(R.string.ui_quiet_acceleration),
                            valueText = "${String.format("%.1f", quietAcceleration)}×",
                            value = quietAcceleration,
                            onValueChange = {
                                quietAcceleration = ((it * 2f).roundToInt() / 2f)
                                    .coerceIn(2f, 8f)
                                PlaybackRuntime.setQuietSpeedMultiplier(quietAcceleration)
                            },
                            onValueChangeFinished = {
                                preferences.edit()
                                    .putFloat("quiet_acceleration", quietAcceleration)
                                    .apply()
                            },
                            valueRange = 2f..8f,
                            steps = 11,
                            enabled = skipSilence
                        )
                    }

                    if (silencePlaybackMode == SilencePlaybackMode.JUMP) {
                        SheetSwitchRow(
                            label = stringResource(R.string.ui_adaptive_silence),
                            checked = adaptiveSilence,
                            onCheckedChange = {
                                adaptiveSilence = it
                                applySilenceSettings()
                            }
                        )
                    }

                    Text(
                        when {
                            !skipSilence -> stringResource(R.string.ui_skipping_off)
                            else -> PlaybackRuntime.analysisStatus
                        },
                        fontSize = 13.sp,
                        color = PlayerAccent,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Text(
                        stringResource(
                            if (silencePlaybackMode == SilencePlaybackMode.SMOOTH) {
                                R.string.ui_silence_smooth_audio_hint
                            } else {
                                R.string.ui_silence_help
                            }
                        ),
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    ChoiceChips(
                        options = SilencePreset.entries.toList(),
                        selected = {
                            SilencePreset.matching(
                                silenceThresholdDb,
                                minimumSilence,
                                edgePadding
                            ) == it
                        },
                        label = { localizePlayerLabel(it.label) },
                        onSelect = {
                            silenceThresholdDb = it.thresholdDb
                            minimumSilence = it.minimumSilence
                            edgePadding = it.edgePadding
                            applySilenceSettings()
                            gestureText = it.label
                        }
                    )

                    OutlinedButton(
                        onClick = { showAdvancedSilence = !showAdvancedSilence },
                        enabled = skipSilence,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.ui_advanced_tuning) +
                                if (showAdvancedSilence) " −" else " +"
                        )
                    }

                    if (showAdvancedSilence) {
                        SheetSliderRow(
                            label = stringResource(R.string.ui_threshold),
                            valueText = "${silenceThresholdDb.roundToInt()} dB",
                            value = silenceThresholdDb,
                            onValueChange = { silenceThresholdDb = it },
                            onValueChangeFinished = { applySilenceSettings() },
                            valueRange = -60f..-20f,
                            steps = 39,
                            enabled = skipSilence
                        )
    
                        SheetSliderRow(
                            label = stringResource(R.string.ui_minimum_silence),
                            valueText = String.format("%.2f s", minimumSilence),
                            value = minimumSilence,
                            onValueChange = {
                                minimumSilence =
                                    ((it * 20).roundToInt() / 20f)
                            },
                            onValueChangeFinished = { applySilenceSettings() },
                            valueRange = 0.2f..2f,
                            steps = 35,
                            enabled = skipSilence
                        )
    
                        SheetSliderRow(
                            label = stringResource(R.string.ui_edge_padding),
                            valueText = "${(edgePadding * 1000).roundToInt()} ms",
                            value = edgePadding,
                            onValueChange = {
                                edgePadding =
                                    ((it * 100).roundToInt() / 100f)
                            },
                            onValueChangeFinished = { applySilenceSettings() },
                            valueRange = 0.02f..0.20f,
                            steps = 17,
                            enabled = skipSilence
                        )
                    }
                }
            }
        }

        if (showSubtitleSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSubtitleSheet = false },
                containerColor = PlayerSheetColor
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 28.dp)
                ) {
                    SheetHeader(stringResource(R.string.ui_subtitles)) {
                        showSubtitleSheet = false
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { openSubtitleFile() },
                            enabled = activeUri != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                subtitleName
                                    ?: stringResource(R.string.ui_import_subtitle),
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

                    SectionLabel(stringResource(R.string.ui_subtitle_tracks))

                    TrackSectionRows(
                        tracks = currentTracks,
                        trackType = C.TRACK_TYPE_TEXT,
                        player = player,
                        includeOff = true
                    )

                    SheetSliderRow(
                        label = stringResource(R.string.ui_text_size),
                        valueText = String.format("%.1f×", subtitleFontScale),
                        value = subtitleFontScale,
                        onValueChange = {
                            subtitleFontScale = it.coerceIn(0.7f, 2f)
                        },
                        onValueChangeFinished = {
                            preferences.edit()
                                .putFloat("subtitle_font_scale", subtitleFontScale)
                                .apply()
                        },
                        valueRange = 0.7f..2f
                    )

                    if (selectedSubtitleUri != null) {
                        OutlinedButton(
                            onClick = { showAdvancedSubtitle = !showAdvancedSubtitle },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.ui_subtitle_advanced) +
                                    if (showAdvancedSubtitle) " −" else " +"
                            )
                        }
                        if (showAdvancedSubtitle) {
                        SheetSliderRow(
                            label = stringResource(R.string.ui_sync),
                            valueText = String.format("%+.1f s", subtitleOffsetMs / 1000f),
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
                            steps = 399
                        )
    
    
                        SheetSliderRow(
                            label = stringResource(R.string.ui_background),
                            valueText = "${(subtitleBackgroundOpacity * 100).roundToInt()}%",
                            value = subtitleBackgroundOpacity,
                            onValueChange = { subtitleBackgroundOpacity = it },
                            onValueChangeFinished = {
                                preferences.edit()
                                    .putFloat(
                                        "subtitle_background_opacity",
                                        subtitleBackgroundOpacity
                                    )
                                    .apply()
                            },
                            valueRange = 0f..1f
                        )
    
                        ChoiceChips(
                            options = SubtitlePosition.entries.toList(),
                            selected = { subtitlePosition == it },
                            label = { localizePlayerLabel(it.label) },
                            onSelect = {
                                subtitlePosition = it
                                preferences.edit()
                                    .putString("subtitle_position", it.name)
                                    .apply()
                            }
                        )
    
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = subtitleSearch,
                                onValueChange = { subtitleSearch = it },
                                singleLine = true,
                                label = {
                                    Text(stringResource(R.string.ui_search_subtitle_text))
                                },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(6.dp))
                            Button(
                                onClick = { searchSubtitle() },
                                enabled = externalSubtitleCues.isNotEmpty()
                            ) {
                                Text(stringResource(R.string.ui_next))
                            }
                        }
                            }
                    } else {
                        Text(
                            stringResource(R.string.ui_subtitle_advanced_hint),
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }

        if (showAudioSheet) {
            ModalBottomSheet(
                onDismissRequest = { showAudioSheet = false },
                containerColor = PlayerSheetColor
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 28.dp)
                ) {
                    SheetHeader(stringResource(R.string.ui_audio)) {
                        showAudioSheet = false
                    }

                    TrackSectionRows(
                        tracks = currentTracks,
                        trackType = C.TRACK_TYPE_AUDIO,
                        player = player,
                        includeOff = false
                    )
                }
            }
        }

        if (showToolsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showToolsSheet = false },
                containerColor = PlayerSheetColor
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 28.dp)
                ) {
                    SheetHeader(stringResource(R.string.ui_tools)) {
                        showToolsSheet = false
                    }
                    if (compactLandscape || compactPortraitActions) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showToolsSheet = false
                                showSpeedSheet = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.ui_speed))
                        }
                        OutlinedButton(
                            onClick = {
                                showToolsSheet = false
                                showSubtitleSheet = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.ui_subtitles))
                        }
                    }
                    if (narrowLandscape) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    showToolsSheet = false
                                    seekBy(-doubleTapSeconds * 1000L)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("−$doubleTapSeconds s")
                            }
                            OutlinedButton(
                                onClick = {
                                    showToolsSheet = false
                                    seekBy(doubleTapSeconds * 1000L)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("+$doubleTapSeconds s")
                            }
                        }
                    }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showToolsSheet = false
                                showAudioSheet = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.ui_audio))
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            showToolsSheet = false
                            showMarkersSheet = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.ui_markers))
                    }

                    SectionLabel(stringResource(R.string.ui_sleep_timer))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(15L, 30L, 45L, 60L).forEach { minutes ->
                            PlayerChip(
                                text = stringResource(
                                    R.string.ui_minutes,
                                    minutes.toInt()
                                ),
                                selected = selectedSleepMinutes == minutes,
                                onClick = {
                                    sleepAtEnd = false
                                    selectedSleepMinutes = minutes
                                    sleepDeadline =
                                        SystemClock.elapsedRealtime() +
                                            minutes * 60_000L
                                }
                            )
                        }
                        PlayerChip(
                            text = stringResource(R.string.ui_at_end_of_video),
                            selected = sleepAtEnd,
                            onClick = {
                                sleepDeadline = null
                                selectedSleepMinutes = null
                                sleepAtEnd = true
                            }
                        )
                        if (sleepDeadline != null || sleepAtEnd) {
                            PlayerChip(
                                text = stringResource(R.string.ui_cancel_timer),
                                selected = false,
                                onClick = {
                                    sleepDeadline = null
                                    selectedSleepMinutes = null
                                    sleepAtEnd = false
                                }
                            )
                        }
                    }

                    SectionLabel(stringResource(R.string.ui_gestures))
                    Text(
                        stringResource(R.string.ui_player_gestures_help),
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
            }
        }

        if (showMarkersSheet) {
            ModalBottomSheet(
                onDismissRequest = { showMarkersSheet = false },
                containerColor = PlayerSheetColor
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 28.dp)
                ) {
                    SheetHeader(stringResource(R.string.ui_markers)) {
                        showMarkersSheet = false
                    }
                    SectionLabel(stringResource(R.string.ui_ab_repeat))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                abStartMs = currentMs
                                if (abEndMs != null && abEndMs!! <= currentMs) {
                                    abEndMs = null
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                abStartMs?.let { "A ${formatTime(it)}" }
                                    ?: stringResource(R.string.ui_set_a),
                                maxLines = 1
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                if (abStartMs != null && currentMs > abStartMs!!) {
                                    abEndMs = currentMs
                                }
                            },
                            enabled = abStartMs != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                abEndMs?.let { "B ${formatTime(it)}" }
                                    ?: stringResource(R.string.ui_set_b),
                                maxLines = 1
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            abStartMs = null
                            abEndMs = null
                        },
                        enabled = abStartMs != null || abEndMs != null,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.ui_clear_a_b))
                    }

                    SectionLabel(stringResource(R.string.ui_bookmarks))

                    OutlinedButton(
                        onClick = { addBookmark() },
                        enabled = activeUri != null
                    ) {
                        Text(stringResource(R.string.ui_add_bookmark))
                    }

                    if (bookmarks.isEmpty()) {
                        Text(
                            stringResource(R.string.ui_no_bookmarks),
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp
                        )
                    } else {
                        bookmarks.forEach { bookmark ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        player.seekTo(bookmark.positionMs)
                                        showMarkersSheet = false
                                        controlsVisible = true
                                    }
                                    .heightIn(min = 48.dp)
                                    .padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Bookmark,
                                    contentDescription = null,
                                    tint = PlayerAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    bookmark.label,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        bookmarks = bookmarks.filterNot {
                                            it.positionMs == bookmark.positionMs
                                        }
                                        activeUri?.let { uri ->
                                            saveBookmarks(preferences, uri, bookmarks)
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(
                                            R.string.ui_remove_bookmark,
                                            bookmark.label
                                        ),
                                        tint = Color.White.copy(alpha = 0.75f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                        TextButton(
                            onClick = {
                                showMarkersSheet = false
                                pendingDestructiveAction = "bookmarks"
                            }
                        ) {
                            Text(stringResource(R.string.ui_clear_bookmarks))
                        }
                    }

                    SectionLabel(stringResource(R.string.ui_chapters))

                    if (PlaybackRuntime.chapters.isEmpty()) {
                        Text(
                            stringResource(R.string.ui_no_chapters),
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp
                        )
                    } else {
                        PlaybackRuntime.chapters.forEach { chapter ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        player.seekTo(chapter.startMs)
                                        showMarkersSheet = false
                                        controlsVisible = true
                                    }
                                    .heightIn(min = 48.dp)
                                    .heightIn(min = 48.dp)
                                    .padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    formatTime(chapter.startMs),
                                    color = PlayerAccent,
                                    fontSize = 12.sp
                                )
                                Text(
                                    chapter.title,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                }
            }
        }

        if (showPlaylistSheet) {
            ModalBottomSheet(
                onDismissRequest = { showPlaylistSheet = false },
                containerColor = PlayerSheetColor
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 28.dp)
                ) {
                    SheetHeader(stringResource(R.string.ui_up_next)) {
                        showPlaylistSheet = false
                    }
                    Text(
                        stringResource(R.string.ui_video_count, player.mediaItemCount),
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 13.sp
                    )

                    for (index in 0 until player.mediaItemCount) {
                        val item = player.getMediaItemAt(index)
                        SelectRow(
                            text = item.mediaMetadata.title?.toString()
                                ?: item.mediaId,
                            selected = index == player.currentMediaItemIndex,
                            onClick = {
                                if (index != player.currentMediaItemIndex) {
                                    player.seekTo(index, 0)
                                    player.prepare()
                                }
                                player.play()
                                showLibrary = false
                                controlsVisible = true
                                showPlaylistSheet = false
                            }
                        )
                    }
                }
            }
        }

        if (showRecentSheet) {
            ModalBottomSheet(
                onDismissRequest = { showRecentSheet = false },
                containerColor = PlayerSheetColor
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 28.dp)
                ) {
                    SheetHeader(stringResource(R.string.ui_recent)) {
                        showRecentSheet = false
                    }

                    if (recentVideos.isEmpty()) {
                        Text(
                            "No recent videos",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp
                        )
                    } else {
                        recentVideos.forEach { recent ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        showRecentSheet = false
                                        requestOpen(
                                            listOf(VideoEntry(Uri.parse(recent.uri), recent.name))
                                        )
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.History,
                                    contentDescription = null,
                                    tint = PlayerAccent
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    recent.name,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2
                                )
                            }
                        }
                        TextButton(onClick = {
                            showRecentSheet = false
                            pendingDestructiveAction = "history"
                        }) {
                            Text(stringResource(R.string.ui_clear_history))
                        }
                    }
                }
            }
        }
    }

    pendingDestructiveAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingDestructiveAction = null },
            title = {
                Text(
                    stringResource(
                        when (action) {
                            "history" -> R.string.ui_confirm_clear_history
                            "bookmarks" -> R.string.ui_confirm_clear_bookmarks
                            else -> R.string.ui_confirm_reset_settings
                        }
                    )
                )
            },
            text = { Text(stringResource(R.string.ui_cannot_undo)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDestructiveAction = null
                    when (action) {
                        "history" -> clearHistory()
                        "settings" -> resetSettings()
                        "bookmarks" -> {
                            bookmarks = emptyList()
                            activeUri?.let { uri ->
                                saveBookmarks(preferences, uri, emptyList())
                            }
                        }
                    }
                }) {
                    Text(
                        stringResource(
                            if (action == "settings") R.string.ui_confirm_reset
                            else R.string.ui_clear
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDestructiveAction = null }) {
                    Text(stringResource(R.string.ui_cancel))
                }
            }
        )
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
private fun HomeScreen(
    recentVideos: List<RecentVideo>,
    recentFolders: List<RecentVideo>,
    onOpenRecentFolder: (RecentVideo) -> Unit,
    libraryError: String?,
    currentVideoName: String?,
    currentPositionMs: Long,
    currentDurationMs: Long,
    queueCount: Int,
    onShowQueue: () -> Unit,
    onSettings: () -> Unit,
    recentResumePositionMs: (RecentVideo) -> Long,
    onContinue: () -> Unit,
    onOpenVideo: () -> Unit,
    onOpenFolder: () -> Unit,
    onOpenRecent: (RecentVideo) -> Unit,
    onSeeAllRecents: () -> Unit,
    onHelp: () -> Unit
) {
    var recentSearch by remember { mutableStateOf("") }
    val filteredRecents = if (recentSearch.isBlank()) recentVideos
        else recentVideos.filter { it.name.contains(recentSearch, ignoreCase = true) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF101720), Color.Black)
                )
            ),
        contentAlignment = if (centerIntro) Alignment.Center else Alignment.TopCenter
    ) {
        if (currentVideoName == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onSettings) {
                    Text(stringResource(R.string.ui_settings))
                }
            }
        }
        Spacer(Modifier.height(if (currentVideoName == null) 12.dp else 24.dp))

        if (currentVideoName == null) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        PlayerAccent.copy(alpha = 0.16f),
                        RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = PlayerAccent,
                    modifier = Modifier.size(34.dp)
                )
            )
    
            Text(
                stringResource(R.string.ui_home_subtitle),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.ui_library),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSettings) {
                    Text(stringResource(R.string.ui_settings))
                }
            }
        }

        if (currentVideoName != null) {
            OutlinedButton(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 22.dp)
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = PlayerAccent
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.ui_continue_watching),
                        color = PlayerAccent,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        currentVideoName,
                        color = Color.White,
                        maxLines = 1,
                        fontSize = 13.sp
                    )
                    if (currentDurationMs > 0) {
                        Text(
                            formatTime(currentPositionMs) + " / " +
                                formatTime(currentDurationMs),
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 12.sp
                        )
                    }
                }
    
                Text(
                    stringResource(R.string.app_name),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    modifier = Modifier.padding(top = 14.dp)
                )
    
                Text(
                    stringResource(R.string.ui_home_subtitle),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            } else {
                Text(
                    stringResource(R.string.ui_library),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 10.dp)
                )
            }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (currentVideoName == null) 28.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onOpenVideo,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PlayerAccent,
                    contentColor = PlayerAccentDark
                )
            ) {
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.ui_open_video),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            OutlinedButton(
                onClick = onOpenFolder,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.ui_open_folder), fontSize = 14.sp)
            }
        }

        if (libraryError != null) {
            Text(
                libraryError,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
            TextButton(onClick = onOpenFolder) {
                Text(stringResource(R.string.ui_choose_another_folder))
            }
        }

        if (recentFolders.isNotEmpty()) {
            Text(
                stringResource(R.string.ui_recent_folders),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 22.dp, bottom = 6.dp)
            )
            recentFolders.take(4).forEach { folder ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRecentFolder(folder) }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = PlayerAccent
                    )
                    Text(
                        folder.name,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (queueCount > 1) {
            OutlinedButton(
                onClick = onShowQueue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Icon(
                    Icons.Outlined.PlaylistPlay,
                    contentDescription = null,
                    tint = PlayerAccent
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ui_up_next), modifier = Modifier.weight(1f))
                Text(stringResource(R.string.ui_video_count, queueCount))
            }
        }

        if (recentVideos.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeCard(
                    label = stringResource(R.string.ui_open_video),
                    icon = Icons.Filled.PlayArrow,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenVideo
                )
                HomeCard(
                    label = stringResource(R.string.ui_folder),
                    icon = Icons.Filled.Folder,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenFolder
                )
            }

            if (recentVideos.size > 4) {
                OutlinedTextField(
                    value = recentSearch,
                    onValueChange = { recentSearch = it },
                    label = { Text(stringResource(R.string.ui_search_recent)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            filteredRecents.take(8).forEach { recent ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRecent(recent) }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 4.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            recent.name,
                            color = Color.White,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                        val resumeMs = recentResumePositionMs(recent)
                        if (resumeMs >= 5_000L) {
                            Text(
                                stringResource(
                                    R.string.ui_resume_at, formatTime(resumeMs)
                                ),
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onSeeAllRecents) {
                        Text(
                            stringResource(R.string.ui_see_all),
                            color = PlayerAccent,
                            fontSize = 12.sp
                        )
                    }
                }

                recentVideos.take(4).forEach { recent ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onOpenRecent(recent) }
                            .padding(horizontal = 4.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Outlined.History,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            recent.name,
                            color = Color.White,
                            fontSize = 14.sp,
                            maxLines = 2
                        )
                    }
                }
            }
            if (filteredRecents.isEmpty()) {
                Text(
                    stringResource(R.string.ui_no_recent_matches),
                    color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }

        Text(
            stringResource(R.string.ui_home_hint),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 26.dp, bottom = 40.dp)
                .fillMaxWidth()
        )
    }
}

@Composable
private fun playerIconButton(
    icon: ImageVector,
    contentDescription: String?,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun PlayerChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    accessibilityLabel: String? = null,
    selectableChoice: Boolean = false,
    modifier: Modifier = Modifier
) {
    val chipSelected = selected
    Row(
        modifier = modifier
            .semantics {
                if (selectableChoice) this.selected = chipSelected
                if (accessibilityLabel != null) contentDescription = accessibilityLabel
            }
            .heightIn(min = 48.dp)
            .background(
                if (selected) {
                    PlayerAccent.copy(alpha = 0.22f)
                } else {
                    Color.White.copy(alpha = 0.12f)
                },
                RoundedCornerShape(50)
            )
            .border(
                1.dp,
                if (selected) {
                    PlayerAccent.copy(alpha = 0.7f)
                } else {
                    Color.White.copy(alpha = 0.06f)
                },
                RoundedCornerShape(50)
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon?.let {
            Icon(
                it,
                contentDescription = null,
                tint = if (selected) PlayerAccent else Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text,
            color = if (selected) PlayerAccent else Color.White,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun <T> ChoiceChips(
    options: List<T>,
    selected: (T) -> Boolean,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            PlayerChip(
                text = label(option),
                selected = selected(option),
                selectableChoice = true,
                onClick = { onSelect(option) }
            )
        }
    }
}

@Composable
private fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    bookmarks: List<BookmarkPoint>,
    silenceRanges: List<SilenceRangeMs>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var scrubPositionMs by remember(durationMs) { mutableStateOf<Long?>(null) }
    val visualSilenceRanges = remember(silenceRanges, durationMs) {
        timelineSilenceSpans(silenceRanges, durationMs)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                contentDescription = seekbarDescription
                stateDescription = formatTime(scrubPositionMs ?: positionMs) +
                    " of " + formatTime(durationMs)
                progressBarRangeInfo = ProgressBarRangeInfo(
                    (scrubPositionMs ?: positionMs)
                        .coerceIn(0L, durationMs.coerceAtLeast(1L)).toFloat(),
                    0f..durationMs.coerceAtLeast(1L).toFloat()
                )
                setProgress { target ->
                    if (durationMs > 0) {
                        onSeek(target.toLong().coerceIn(0L, durationMs))
                        true
                    } else false
                }
            }
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0) {
                        val fraction =
                            (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeek((fraction * durationMs).toLong())
                    }
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (durationMs > 0) {
                            scrubPositionMs = (
                                offset.x / size.width.coerceAtLeast(1).toFloat() * durationMs
                            ).toLong().coerceIn(0L, durationMs)
                        }
                    },
                    onDragEnd = {
                        scrubPositionMs?.let(onSeek)
                        scrubPositionMs = null
                    },
                    onDragCancel = { scrubPositionMs = null },
                    onHorizontalDrag = { change, _ ->
                        if (durationMs > 0) {
                            change.consume()
                            val fraction = (change.position.x /
                                size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                            scrubPositionMs = (fraction * durationMs).toLong()
                        }
                    }
                )
            }
    ) {
        val fraction = if (durationMs > 0) {
            ((scrubPositionMs ?: positionMs).toFloat() / durationMs.toFloat())
                .coerceIn(0f, 1f)
        } else {
            0f
        }
        Canvas(Modifier.fillMaxSize()) {
            val barCy = size.height / 2f
            val barHeight = 4.dp.toPx()

            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                topLeft = Offset(0f, barCy - barHeight / 2f),
                size = Size(size.width, barHeight),
                cornerRadius = CornerRadius(barHeight / 2f)
            )

            if (durationMs > 0) {
                visualSilenceRanges.forEach { range ->
                    val start =
                        (range.startMs.toFloat() / durationMs.toFloat())
                            .coerceIn(0f, 1f)
                    val end =
                        (range.endMs.toFloat() / durationMs.toFloat())
                            .coerceIn(start, 1f)
                    val left = size.width * start
                    val width = (size.width * (end - start)).coerceAtLeast(1.dp.toPx())
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.42f),
                        topLeft = Offset(left, barCy - barHeight),
                        size = Size(width, barHeight * 2f),
                        cornerRadius = CornerRadius(barHeight)
                    )
                }
            }

            val progressWidth = (size.width * fraction).coerceAtLeast(barHeight)
            drawRoundRect(
                color = PlayerAccent,
                topLeft = Offset(0f, barCy - barHeight / 2f),
                size = Size(progressWidth, barHeight),
                cornerRadius = CornerRadius(barHeight / 2f)
            )

            if (durationMs > 0) {
                bookmarks.forEach { bookmark ->
                    val marker =
                        (bookmark.positionMs.toFloat() / durationMs.toFloat())
                            .coerceIn(0f, 1f)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.9f),
                        radius = 2.5.dp.toPx(),
                        center = Offset(size.width * marker, barCy)
                    )
                }
            }

            val thumbRadius = 6.dp.toPx()
            val thumbX = (size.width * fraction).coerceIn(
                thumbRadius,
                (size.width - thumbRadius).coerceAtLeast(thumbRadius)
            )
            drawCircle(
                color = PlayerAccent,
                radius = thumbRadius,
                center = Offset(thumbX, barCy)
            )
            drawCircle(
                color = Color.White,
                radius = 2.dp.toPx(),
                center = Offset(thumbX, barCy)
            )
        }
    }
}

@Composable
private fun SheetHeader(
    title: String,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onClose) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = PlayerAccent,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun SheetSliderRow(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                valueText,
                color = PlayerAccent,
                fontSize = 12.sp
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled
        )
    }
}

@Composable
private fun SheetSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    // One switch semantics node for both the label and the control. Users
    // can tap the whole row and TalkBack won't announce two separate toggles.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = null
        )
    }
}

@Composable
private fun SelectRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = null
        )
        Text(
            text,
            color = if (selected) PlayerAccent else Color.White,
            fontSize = 14.sp,
            maxLines = 2
        )
    }
}

@Composable
private fun TrackSectionRows(
    tracks: Tracks?,
    trackType: Int,
    player: Player,
    includeOff: Boolean
) {
    val groups = tracks?.groups?.filter { it.type == trackType }.orEmpty()

    if (includeOff) {
        val offSelected =
            player.trackSelectionParameters.disabledTrackTypes.contains(trackType)
        SelectRow(
            text = stringResource(R.string.ui_off),
            selected = offSelected,
            onClick = {
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .clearOverridesOfType(trackType)
                        .setTrackTypeDisabled(trackType, true)
                        .build()
            }
        )
    } else {
        val hasOverride = player.trackSelectionParameters.overrides
            .any { it.key.type == trackType }
        SelectRow(
            text = stringResource(R.string.ui_auto),
            selected = !hasOverride,
            onClick = {
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .clearOverridesOfType(trackType)
                        .setTrackTypeDisabled(trackType, false)
                        .build()
            }
        )
    }

    val overridesForType = player.trackSelectionParameters.overrides
        .filterKeys { it.type == trackType }.values

    groups.forEach { group ->
        for (index in 0 until group.length) {
            val matchingOverride = overridesForType.any { override ->
                override.mediaTrackGroup == group.mediaTrackGroup &&
                    override.trackIndices.contains(index)
            }
            val isSelected = if (overridesForType.isNotEmpty()) {
                matchingOverride
            } else {
                group.isTrackSelected(index)
            }
            SelectRow(
                text = trackLabel(group, index),
                selected = isSelected,
                onClick = {
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

private fun selectedTrackName(tracks: Tracks?, trackType: Int): String? {
    return tracks?.groups
        ?.filter { it.type == trackType }
        ?.firstNotNullOfOrNull { group ->
            (0 until group.length)
                .firstOrNull { group.isTrackSelected(it) }
                ?.let { trackLabel(group, it) }
        }
}

private fun formatSpeedLabel(speed: Float): String {
    val formatted = String.format("%.2f", speed)
        .trimEnd('0')
        .trimEnd('.')
    return "${formatted}×"
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
    data class LocatedDocument(
        val uri: Uri,
        val name: String,
        val parentId: String
    )

    val videos = mutableListOf<LocatedDocument>()
    val subtitles = mutableListOf<LocatedDocument>()
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    val videoExtensions = setOf(
        "mkv", "webm", "mp4", "m4v", "mov", "avi", "ts", "mts", "m2ts"
    )
    val subtitleExtensions = setOf("srt", "vtt", "ass", "ssa", "ttml", "xml")

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
                        continue
                    }

                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    val extension = name.substringAfterLast('.', "").lowercase()

                    when {
                        mime.startsWith("video/") || extension in videoExtensions ->
                            videos += LocatedDocument(uri, name, documentId)
                        extension in subtitleExtensions ->
                            subtitles += LocatedDocument(uri, name, documentId)
                    }
                }
            }
        }
    }

    walk(rootId)

    return videos
        .sortedBy { it.name.lowercase() }
        .map { video ->
            val candidates = subtitles.filter {
                it.parentId == video.parentId &&
                    subtitleMatchesVideo(video.name, it.name)
            }
            val exactBase = video.name.substringBeforeLast('.', video.name).lowercase()
            val subtitle = candidates.minByOrNull { candidate ->
                val subtitleBase =
                    candidate.name.substringBeforeLast('.', candidate.name).lowercase()
                if (subtitleBase == exactBase) 0 else 1
            }?.uri

            VideoEntry(
                uri = video.uri,
                name = video.name,
                subtitleUri = subtitle
            )
        }
}

private fun canReadUri(context: Context, uri: Uri): Boolean {
    return runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
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
    val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    runCatching {
        activity.contentResolver.takePersistableUriPermission(uri, read or write)
    }.onFailure {
        runCatching {
            activity.contentResolver.takePersistableUriPermission(uri, read)
        }
    }
}

private fun loadRecentVideos(
    preferences: android.content.SharedPreferences,
    key: String = "recent_videos"
): List<RecentVideo> {
    val raw = preferences.getString(key, null) ?: return emptyList()
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
    videos: List<RecentVideo>,
    key: String = "recent_videos"
) {
    val array = JSONArray()
    videos.take(12).forEach {
        array.put(
            JSONObject()
                .put("uri", it.uri)
                .put("name", it.name)
        )
    }
    preferences.edit().putString(key, array.toString()).apply()
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
