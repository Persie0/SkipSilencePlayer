package at.persie0.skipsilenceplayer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import kotlin.math.roundToInt

data class MediaDiagnostics(
    val summary: String,
    val unsupportedSummary: String?
)

fun buildMediaDiagnostics(tracks: Tracks?): MediaDiagnostics {
    if (tracks == null) return MediaDiagnostics("", null)

    val video = representativeTrack(tracks, C.TRACK_TYPE_VIDEO)
    val audio = representativeTrack(tracks, C.TRACK_TYPE_AUDIO)

    val parts = buildList {
        video?.let { (format, supported) ->
            val codec = humanCodec(format)
            if (codec.isNotBlank()) add(codec)
            if (format.width > 0 && format.height > 0) {
                add("${format.width}×${format.height}")
            }
            if (format.frameRate > 0f) {
                add("${format.frameRate.roundToInt()} fps")
            }
            if (!supported) add("video unsupported")
        }
        audio?.let { (format, supported) ->
            val codec = humanCodec(format)
            if (codec.isNotBlank()) add(codec)
            if (format.channelCount > 0) add("${format.channelCount}ch")
            if (format.sampleRate > 0) add("${format.sampleRate / 1000.0} kHz")
            if (!supported) add("audio unsupported")
        }
    }

    val unsupported = buildList {
        if (
            tracks.containsType(C.TRACK_TYPE_VIDEO) &&
            !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)
        ) {
            add(
                video?.first?.sampleMimeType
                    ?.let { "Unsupported video format: $it" }
                    ?: "Video format is not supported on this device"
            )
        }
        if (
            tracks.containsType(C.TRACK_TYPE_AUDIO) &&
            !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
        ) {
            add(
                audio?.first?.sampleMimeType
                    ?.let { "Unsupported audio format: $it" }
                    ?: "Audio format is not supported on this device"
            )
        }
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    return MediaDiagnostics(
        summary = parts.joinToString(" · "),
        unsupportedSummary = unsupported
    )
}

private fun representativeTrack(
    tracks: Tracks,
    type: Int
): Pair<Format, Boolean>? {
    val groups = tracks.groups.filter { it.type == type }
    for (group in groups) {
        for (index in 0 until group.length) {
            if (group.isTrackSelected(index)) {
                return group.getTrackFormat(index) to group.isTrackSupported(index)
            }
        }
    }
    val first = groups.firstOrNull() ?: return null
    if (first.length <= 0) return null
    return first.getTrackFormat(0) to first.isTrackSupported(0)
}

private fun humanCodec(format: Format): String {
    val codec = format.codecs
        ?.substringBefore(',')
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    if (codec != null) {
        return when {
            codec.startsWith("avc1", true) || codec.startsWith("avc3", true) -> "H.264"
            codec.startsWith("hvc1", true) || codec.startsWith("hev1", true) -> "HEVC"
            codec.startsWith("av01", true) -> "AV1"
            codec.startsWith("vp09", true) || codec.startsWith("vp9", true) -> "VP9"
            codec.startsWith("vp08", true) || codec.startsWith("vp8", true) -> "VP8"
            codec.startsWith("mp4a", true) -> "AAC"
            codec.startsWith("opus", true) -> "Opus"
            codec.startsWith("vorbis", true) -> "Vorbis"
            else -> codec
        }
    }

    return when (format.sampleMimeType) {
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "audio/mp4a-latm" -> "AAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        else -> format.sampleMimeType.orEmpty().substringAfter('/')
    }
}
