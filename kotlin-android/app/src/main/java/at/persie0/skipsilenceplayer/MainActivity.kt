@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package at.persie0.skipsilenceplayer

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    var pipMode by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme {
                SkipSilencePlayerScreen(this)
            }
        }
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
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(safeWidth, safeHeight))
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

private data class PlayerBundle(
    val player: ExoPlayer,
    val silenceProcessor: SilenceSkippingAudioProcessor
)

private data class RecentVideo(
    val uri: String,
    val name: String
)

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
                kotlin.math.abs(it.thresholdDb - threshold) < 0.01f &&
                    kotlin.math.abs(it.minimumSilence - minimum) < 0.01f &&
                    kotlin.math.abs(it.edgePadding - padding) < 0.01f
            }
        }
    }
}

@Composable
private fun SkipSilencePlayerScreen(activity: MainActivity) {
    val context = activity.applicationContext
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
    var appliedThresholdDb by remember { mutableFloatStateOf(silenceThresholdDb) }
    var appliedMinimumSilence by remember { mutableFloatStateOf(minimumSilence) }
    var appliedEdgePadding by remember { mutableFloatStateOf(edgePadding) }

    var playbackSpeed by remember {
        mutableFloatStateOf(preferences.getFloat("playback_speed", 1f))
    }
    var doubleTapSeconds by remember {
        mutableIntStateOf(preferences.getInt("double_tap_seconds", 10))
    }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedSubtitleUri by remember { mutableStateOf<Uri?>(null) }
    var subtitleName by remember { mutableStateOf<String?>(null) }
    var mediaRevision by remember { mutableIntStateOf(0) }
    var restorePositionMs by remember { mutableLongStateOf(0L) }
    var restorePlaying by remember { mutableStateOf(true) }

    var fileName by remember { mutableStateOf("No video selected") }
    var fileSizeText by remember { mutableStateOf("") }
    var fileInfo by remember { mutableStateOf("") }
    var currentMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var gestureText by remember { mutableStateOf<String?>(null) }
    var currentTracks by remember { mutableStateOf<Tracks?>(null) }

    var recentVideos by remember {
        mutableStateOf(loadRecentVideos(preferences))
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }

    var skippedCarrySeconds by remember { mutableFloatStateOf(0f) }
    var previousSegmentSkippedSeconds by remember { mutableFloatStateOf(0f) }
    var skippedSeconds by remember { mutableFloatStateOf(0f) }

    val playerBundle = remember(
        appliedThresholdDb,
        appliedMinimumSilence,
        appliedEdgePadding
    ) {
        buildPlayer(
            context = context,
            silenceThresholdDb = appliedThresholdDb,
            minimumSilenceSeconds = appliedMinimumSilence,
            edgePaddingSeconds = appliedEdgePadding
        )
    }
    val player = playerBundle.player

    val latestBrightness = rememberUpdatedState(brightness)
    val latestVolume = rememberUpdatedState(volume)
    val latestDoubleTapSeconds = rememberUpdatedState(doubleTapSeconds)

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

    fun saveCurrentVideoState() {
        val uri = selectedUri ?: return
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

    fun applySilenceSettings(
        threshold: Float = silenceThresholdDb,
        minimum: Float = minimumSilence,
        padding: Float = edgePadding
    ) {
        restorePositionMs = player.currentPosition
        restorePlaying = player.isPlaying
        skippedCarrySeconds += previousSegmentSkippedSeconds
        previousSegmentSkippedSeconds = 0f

        silenceThresholdDb = threshold.coerceIn(-60f, -20f)
        minimumSilence = minimum.coerceIn(0.2f, 2f)
        edgePadding = padding.coerceIn(0.02f, 0.20f)

        appliedThresholdDb = silenceThresholdDb
        appliedMinimumSilence = minimumSilence
        appliedEdgePadding = edgePadding

        preferences.edit()
            .putFloat("silence_threshold_db", silenceThresholdDb)
            .putFloat("minimum_silence", minimumSilence)
            .putFloat("edge_padding", edgePadding)
            .apply()
        saveCurrentVideoState()
    }

    fun openVideo(uri: Uri, name: String) {
        saveCurrentVideoState()

        val key = mediaKey(uri)
        val threshold = preferences.getFloat("${key}_threshold", preferences.getFloat("silence_threshold_db", -42f))
        val minimum = preferences.getFloat("${key}_minimum", preferences.getFloat("minimum_silence", 0.45f))
        val padding = preferences.getFloat("${key}_padding", preferences.getFloat("edge_padding", 0.08f))

        silenceThresholdDb = threshold
        minimumSilence = minimum
        edgePadding = padding
        appliedThresholdDb = threshold
        appliedMinimumSilence = minimum
        appliedEdgePadding = padding
        playbackSpeed = preferences.getFloat("${key}_speed", preferences.getFloat("playback_speed", 1f))
        skipSilence = preferences.getBoolean("${key}_skip", preferences.getBoolean("skip_silence", true))

        val storedSubtitle = preferences.getString("${key}_subtitle", null)
        selectedSubtitleUri = storedSubtitle?.let(Uri::parse)
        subtitleName = selectedSubtitleUri?.lastPathSegment?.substringAfterLast('/')

        restorePositionMs = preferences.getLong("${key}_position", 0L)
        restorePlaying = true
        fileName = name
        fileSizeText = queryFileSize(context, uri)
        selectedUri = uri
        mediaRevision++

        skippedCarrySeconds = 0f
        previousSegmentSkippedSeconds = 0f
        skippedSeconds = 0f

        val updated = listOf(RecentVideo(uri.toString(), name)) +
            recentVideos.filterNot { it.uri == uri.toString() }
        recentVideos = updated.take(8)
        saveRecentVideos(preferences, recentVideos)
        controlsVisible = true
    }

    fun seekBy(deltaMs: Long) {
        if (selectedUri == null) return
        val end = if (durationMs > 0) durationMs else Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, end))
        gestureText = if (deltaMs < 0) {
            "−${latestDoubleTapSeconds.value} s"
        } else {
            "+${latestDoubleTapSeconds.value} s"
        }
        controlsVisible = true
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity.setPlayerFullscreen(false)
        }
    }

    LaunchedEffect(skipSilence, player) {
        player.skipSilenceEnabled = skipSilence
        preferences.edit().putBoolean("skip_silence", skipSilence).apply()
    }

    LaunchedEffect(playbackSpeed, player) {
        player.setPlaybackSpeed(playbackSpeed.coerceIn(0.5f, 3f))
    }

    LaunchedEffect(player, selectedUri, selectedSubtitleUri, mediaRevision) {
        selectedUri?.let { uri ->
            player.setMediaItem(buildMediaItem(uri, selectedSubtitleUri))
            player.prepare()
            if (restorePositionMs > 0L) {
                player.seekTo(restorePositionMs)
            }
            player.setPlaybackSpeed(playbackSpeed)
            if (restorePlaying) {
                player.play()
            }
        }
    }

    LaunchedEffect(player) {
        var tick = 0
        while (isActive) {
            currentMs = player.currentPosition.coerceAtLeast(0L)
            val value = player.duration
            durationMs = if (value != C.TIME_UNSET && value > 0) value else 0L
            isPlaying = player.isPlaying
            currentTracks = player.currentTracks

            val sampleRate = player.audioFormat?.sampleRate?.takeIf { it > 0 } ?: 48_000
            val segmentSkipped =
                playerBundle.silenceProcessor.skippedFrames.toFloat() / sampleRate.toFloat()
            if (segmentSkipped + 0.02f < previousSegmentSkippedSeconds) {
                skippedCarrySeconds += previousSegmentSkippedSeconds
            }
            previousSegmentSkippedSeconds = segmentSkipped
            skippedSeconds = skippedCarrySeconds + segmentSkipped

            val videoFormat = player.videoFormat
            val resolution = if (
                videoFormat != null &&
                videoFormat.width > 0 &&
                videoFormat.height > 0
            ) {
                "${videoFormat.width}×${videoFormat.height}"
            } else {
                ""
            }
            fileInfo = listOf(
                fileSizeText,
                resolution,
                if (durationMs > 0) formatTime(durationMs) else ""
            ).filter { it.isNotBlank() }.joinToString(" · ")

            tick++
            if (tick % 5 == 0) {
                val uri = selectedUri
                if (uri != null) {
                    val key = mediaKey(uri)
                    preferences.edit()
                        .putLong("${key}_position", currentMs)
                        .putFloat("${key}_speed", playbackSpeed)
                        .putFloat("${key}_threshold", silenceThresholdDb)
                        .putFloat("${key}_minimum", minimumSilence)
                        .putFloat("${key}_padding", edgePadding)
                        .putBoolean("${key}_skip", skipSilence)
                        .apply()
                }
            }
            delay(200)
        }
    }

    LaunchedEffect(gestureText) {
        if (gestureText != null) {
            delay(700)
            gestureText = null
        }
    }

    LaunchedEffect(isPlaying, controlsVisible, isFullscreen, activity.pipMode) {
        if (isPlaying && controlsVisible && !activity.pipMode) {
            delay(if (isFullscreen) 2_500 else 4_000)
            controlsVisible = false
        }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            takePersistableReadPermission(activity, uri)
            openVideo(
                uri,
                uri.lastPathSegment?.substringAfterLast('/') ?: "Video"
            )
        }
    }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && selectedUri != null) {
            takePersistableReadPermission(activity, uri)
            restorePositionMs = player.currentPosition
            restorePlaying = player.isPlaying
            selectedSubtitleUri = uri
            subtitleName = uri.lastPathSegment?.substringAfterLast('/') ?: "External subtitle"
            val key = mediaKey(selectedUri!!)
            preferences.edit().putString("${key}_subtitle", uri.toString()).apply()
            mediaRevision++
            controlsVisible = true
        }
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
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(player, doubleTapSeconds) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                        },
                        onDoubleTap = { offset ->
                            val delta = latestDoubleTapSeconds.value * 1000L
                            if (offset.x < size.width / 2f) {
                                seekBy(-delta)
                            } else {
                                seekBy(delta)
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    var leftSide = true
                    var startValue = 0f
                    var accumulated = 0f

                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            leftSide = offset.x < size.width / 2f
                            startValue = if (leftSide) {
                                latestBrightness.value
                            } else {
                                latestVolume.value
                            }
                            accumulated = 0f
                            controlsVisible = true
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            accumulated -= dragAmount
                            val height = max(size.height.toFloat(), 1f)
                            val next = (startValue + accumulated / height).coerceIn(0f, 1f)
                            if (leftSide) {
                                setBrightness(next)
                                gestureText = "Brightness " + (next * 100).roundToInt() + "%"
                            } else {
                                setVolume(next)
                                gestureText = "Volume " + (next * 100).roundToInt() + "%"
                            }
                        }
                    )
                }
        )

        if (selectedUri == null && !activity.pipMode) {
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
                Text("Open video")
            }
        }

        gestureText?.let { message ->
            if (!activity.pipMode) {
                Text(
                    text = message,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.72f), MaterialTheme.shapes.medium)
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }

        if (controlsVisible && !activity.pipMode) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = if (isFullscreen) 330.dp else 500.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.96f))
                        )
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp)
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
                        Spacer(Modifier.width(6.dp))
                        Text("Open")
                    }

                    Spacer(Modifier.width(8.dp))

                    RecentMenu(
                        videos = recentVideos,
                        onOpen = { recent ->
                            openVideo(Uri.parse(recent.uri), recent.name)
                        }
                    )

                    Spacer(Modifier.weight(1f))

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && selectedUri != null) {
                        TextButton(
                            onClick = {
                                val videoSize = player.videoSize
                                activity.enterPlayerPip(videoSize.width, videoSize.height)
                            }
                        ) {
                            Text("PiP")
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
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                if (fileInfo.isNotBlank()) {
                    Text(
                        text = fileInfo,
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Spacer(Modifier.height(4.dp))

                Slider(
                    value = currentMs.coerceAtMost(max(durationMs, 0L)).toFloat(),
                    onValueChange = { value ->
                        if (durationMs > 0L) {
                            player.seekTo(value.toLong())
                            controlsVisible = true
                        }
                    },
                    valueRange = 0f..max(durationMs.toFloat(), 1f),
                    enabled = durationMs > 0L
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatTime(currentMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        "Skipped ${formatTime((skippedSeconds * 1000f).toLong())}",
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        if (durationMs > 0L) {
                            "−" + formatTime((durationMs - currentMs).coerceAtLeast(0L))
                        } else {
                            "--:--"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { seekBy(-doubleTapSeconds * 1000L) },
                        enabled = selectedUri != null
                    ) {
                        Icon(
                            Icons.Default.FastRewind,
                            contentDescription = "Back $doubleTapSeconds seconds",
                            tint = Color.White
                        )
                    }
                    IconButton(
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                            controlsVisible = true
                        },
                        enabled = selectedUri != null
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    IconButton(
                        onClick = { seekBy(doubleTapSeconds * 1000L) },
                        enabled = selectedUri != null
                    ) {
                        Icon(
                            Icons.Default.FastForward,
                            contentDescription = "Forward $doubleTapSeconds seconds",
                            tint = Color.White
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Speed ${String.format("%.2f", playbackSpeed)}×",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(105.dp)
                    )
                    Slider(
                        value = playbackSpeed,
                        onValueChange = {
                            playbackSpeed = ((it * 4).roundToInt() / 4f).coerceIn(0.5f, 3f)
                            player.setPlaybackSpeed(playbackSpeed)
                        },
                        onValueChangeFinished = {
                            preferences.edit().putFloat("playback_speed", playbackSpeed).apply()
                            saveCurrentVideoState()
                        },
                        valueRange = 0.5f..3f,
                        steps = 9,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Skip silence", color = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = skipSilence,
                        onCheckedChange = {
                            skipSilence = it
                            player.skipSilenceEnabled = it
                            saveCurrentVideoState()
                        }
                    )
                    Spacer(Modifier.weight(1f))
                    PresetMenu(
                        current = SilencePreset.matching(
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
                            gestureText = preset.label
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
                        onValueChange = { setBrightness(it) },
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
                        onValueChange = { setVolume(it) },
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Threshold ${silenceThresholdDb.roundToInt()} dB",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(125.dp)
                        )
                        Slider(
                            value = silenceThresholdDb,
                            onValueChange = { silenceThresholdDb = it },
                            onValueChangeFinished = {
                                applySilenceSettings()
                            },
                            valueRange = -60f..-20f,
                            steps = 39,
                            enabled = skipSilence,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Min ${String.format("%.2f", minimumSilence)} s",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(125.dp)
                        )
                        Slider(
                            value = minimumSilence,
                            onValueChange = {
                                minimumSilence = ((it * 20).roundToInt() / 20f)
                            },
                            onValueChangeFinished = {
                                applySilenceSettings()
                            },
                            valueRange = 0.2f..2f,
                            steps = 35,
                            enabled = skipSilence,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Edge ${(edgePadding * 1000).roundToInt()} ms",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(125.dp)
                        )
                        Slider(
                            value = edgePadding,
                            onValueChange = {
                                edgePadding = ((it * 100).roundToInt() / 100f)
                            },
                            onValueChangeFinished = {
                                applySilenceSettings()
                            },
                            valueRange = 0.02f..0.20f,
                            steps = 17,
                            enabled = skipSilence,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DoubleTapMenu(
                            seconds = doubleTapSeconds,
                            onSelect = {
                                doubleTapSeconds = it
                                preferences.edit().putInt("double_tap_seconds", it).apply()
                            }
                        )

                        Spacer(Modifier.width(8.dp))

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
                            enabled = selectedUri != null
                        ) {
                            Text(
                                subtitleName?.let { "Subtitle: $it" } ?: "Load subtitle",
                                maxLines = 1
                            )
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
                        title = "Subtitles",
                        tracks = currentTracks,
                        trackType = C.TRACK_TYPE_TEXT,
                        player = player,
                        includeOff = true
                    )

                    Text(
                        "External subtitles: SRT, VTT, SSA/ASS and TTML when supported by Media3.",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall
                    )

                    TextButton(
                        onClick = {
                            silenceThresholdDb = -42f
                            minimumSilence = 0.45f
                            edgePadding = 0.08f
                            playbackSpeed = 1f
                            doubleTapSeconds = 10
                            skipSilence = true
                            selectedSubtitleUri = null
                            subtitleName = null
                            preferences.edit()
                                .putFloat("silence_threshold_db", -42f)
                                .putFloat("minimum_silence", 0.45f)
                                .putFloat("edge_padding", 0.08f)
                                .putFloat("playback_speed", 1f)
                                .putInt("double_tap_seconds", 10)
                                .putBoolean("skip_silence", true)
                                .apply()
                            applySilenceSettings(-42f, 0.45f, 0.08f)
                            player.setPlaybackSpeed(1f)
                            mediaRevision++
                            gestureText = "Defaults restored"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset player settings")
                    }
                }

                Text(
                    "Double-tap: ±$doubleTapSeconds s · Swipe left: brightness · Swipe right: volume",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
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
            Text("Recent")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            videos.forEach { video ->
                DropdownMenuItem(
                    text = { Text(video.name, maxLines = 1) },
                    onClick = {
                        expanded = false
                        onOpen(video)
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
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SilencePreset.entries.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.label) },
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
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("Double-tap: $seconds s")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            listOf(5, 10, 15, 30).forEach { value ->
                DropdownMenuItem(
                    text = { Text("$value seconds") },
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
private fun TrackMenu(
    title: String,
    tracks: Tracks?,
    trackType: Int,
    player: ExoPlayer,
    includeOff: Boolean
) {
    val groups = tracks?.groups?.filter { it.type == trackType }.orEmpty()
    var expanded by remember { mutableStateOf(false) }
    val selectedName = groups.firstNotNullOfOrNull { group ->
        (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { index ->
            trackLabel(group, index)
        }
    } ?: if (includeOff) "Off/Auto" else "Auto"

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = groups.isNotEmpty() || includeOff,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("$title: $selectedName", maxLines = 1)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (includeOff) "Off" else "Auto") },
                onClick = {
                    expanded = false
                    val builder = player.trackSelectionParameters
                        .buildUpon()
                        .clearOverridesOfType(trackType)

                    if (includeOff) {
                        builder.setTrackTypeDisabled(trackType, true)
                    } else {
                        builder.setTrackTypeDisabled(trackType, false)
                    }
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
    val base = format.label
        ?: format.language?.uppercase()
        ?: "Track ${index + 1}"

    return if (group.type == C.TRACK_TYPE_AUDIO) {
        val channels = format.channelCount.takeIf { it > 0 }?.let { " · ${it}ch" } ?: ""
        "$base$channels"
    } else {
        base
    }
}

private fun buildMediaItem(videoUri: Uri, subtitleUri: Uri?): MediaItem {
    val builder = MediaItem.Builder().setUri(videoUri)
    if (subtitleUri != null) {
        val mime = subtitleMimeType(subtitleUri)
        val subtitle = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
            .setMimeType(mime)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setLabel("External")
            .build()
        builder.setSubtitleConfigurations(listOf(subtitle))
    }
    return builder.build()
}

private fun subtitleMimeType(uri: Uri): String {
    val path = uri.toString().lowercase()
    return when {
        path.endsWith(".vtt") -> "text/vtt"
        path.endsWith(".ass") || path.endsWith(".ssa") -> "text/x-ssa"
        path.endsWith(".ttml") || path.endsWith(".xml") -> "application/ttml+xml"
        else -> "application/x-subrip"
    }
}

@Suppress("DEPRECATION")
private fun buildPlayer(
    context: Context,
    silenceThresholdDb: Float,
    minimumSilenceSeconds: Float,
    edgePaddingSeconds: Float
): PlayerBundle {
    val minimumUs = (minimumSilenceSeconds.coerceIn(0.2f, 2f) * 1_000_000L).toLong()
    val requestedPaddingUs = (edgePaddingSeconds.coerceIn(0.02f, 0.20f) * 1_000_000L).toLong()
    val paddingUs = requestedPaddingUs.coerceAtMost((minimumUs / 2L).coerceAtLeast(1L))

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
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .setAudioProcessorChain(chain)
                .build()
        }
    }
    return PlayerBundle(
        player = ExoPlayer.Builder(context, renderersFactory).build(),
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

private fun mediaKey(uri: Uri): String {
    return "media_" + uri.toString().hashCode().toUInt().toString(16)
}

private fun takePersistableReadPermission(activity: Activity, uri: Uri) {
    try {
        activity.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    } catch (_: SecurityException) {
        // Some providers only grant process-lifetime access.
    }
}

private fun queryFileSize(context: Context, uri: Uri): String {
    return try {
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
    } catch (_: Exception) {
        ""
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format("%.2f GB", mb / 1024.0)
    } else {
        String.format("%.1f MB", mb)
    }
}

private fun loadRecentVideos(
    preferences: android.content.SharedPreferences
): List<RecentVideo> {
    val raw = preferences.getString("recent_videos", null) ?: return emptyList()
    return try {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val uri = item.optString("uri")
                if (uri.isBlank()) continue
                add(
                    RecentVideo(
                        uri = uri,
                        name = item.optString("name", "Video")
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveRecentVideos(
    preferences: android.content.SharedPreferences,
    videos: List<RecentVideo>
) {
    val array = JSONArray()
    videos.take(8).forEach { video ->
        array.put(
            JSONObject()
                .put("uri", video.uri)
                .put("name", video.name)
        )
    }
    preferences.edit().putString("recent_videos", array.toString()).apply()
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
