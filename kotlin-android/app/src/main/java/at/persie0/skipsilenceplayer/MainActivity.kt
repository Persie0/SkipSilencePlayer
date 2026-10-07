package at.persie0.skipsilenceplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
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
}

@Composable
private fun SkipSilencePlayerScreen(activity: Activity) {
    val context = activity.applicationContext
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
                ?: (Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    128
                ) / 255f)
        )
    }
    var volume by remember {
        mutableFloatStateOf(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
        )
    }
    var skipSilence by remember { mutableStateOf(true) }
    var mediaLoaded by remember { mutableStateOf(false) }
    var fileName by remember { mutableStateOf("No video selected") }
    var currentMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var gestureText by remember { mutableStateOf<String?>(null) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            skipSilenceEnabled = true
        }
    }

    val latestBrightness = rememberUpdatedState(brightness)
    val latestVolume = rememberUpdatedState(volume)

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

    fun seekBy(deltaMs: Long) {
        if (!mediaLoaded) return
        val end = if (durationMs > 0) durationMs else Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, end))
        gestureText = if (deltaMs < 0) "−10 s" else "+10 s"
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(skipSilence) {
        player.skipSilenceEnabled = skipSilence
    }

    LaunchedEffect(player) {
        while (isActive) {
            currentMs = player.currentPosition.coerceAtLeast(0L)
            val value = player.duration
            durationMs = if (value != C.TIME_UNSET && value > 0) value else 0L
            isPlaying = player.isPlaying
            delay(200)
        }
    }

    LaunchedEffect(gestureText) {
        if (gestureText != null) {
            delay(700)
            gestureText = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers grant access only for the active process.
            }

            fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Video"
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.play()
            mediaLoaded = true
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
                .pointerInput(player) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            if (offset.x < size.width / 2f) {
                                seekBy(-10_000L)
                            } else {
                                seekBy(10_000L)
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

        if (!mediaLoaded) {
            Button(
                onClick = {
                    picker.launch(
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

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        picker.launch(
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

                Spacer(Modifier.weight(1f))
                Text("Skip silence", color = Color.White)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = skipSilence,
                    onCheckedChange = { skipSilence = it }
                )
            }

            Text(
                text = fileName,
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )

            Spacer(Modifier.height(6.dp))

            Slider(
                value = currentMs.coerceAtMost(max(durationMs, 0L)).toFloat(),
                onValueChange = { value ->
                    if (durationMs > 0L) player.seekTo(value.toLong())
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
                IconButton(onClick = { seekBy(-10_000L) }, enabled = mediaLoaded) {
                    Icon(
                        Icons.Default.FastRewind,
                        contentDescription = "Back 10 seconds",
                        tint = Color.White
                    )
                }
                IconButton(
                    onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                    },
                    enabled = mediaLoaded
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
                IconButton(onClick = { seekBy(10_000L) }, enabled = mediaLoaded) {
                    Icon(
                        Icons.Default.FastForward,
                        contentDescription = "Forward 10 seconds",
                        tint = Color.White
                    )
                }
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

            Text(
                "Double-tap left/right: ±10 s · Swipe left: brightness · Swipe right: volume",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
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
