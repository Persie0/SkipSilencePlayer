package at.persie0.skipsilenceplayer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.charset.Charset
import javax.xml.XMLConstants
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * Subtitle cues are sorted by start time by all parsers. Locate a cue with
 * binary search rather than rescanning every subtitle on each playback tick.
 */
internal fun activeSubtitleCue(
    cues: List<SubtitleCue>,
    positionMs: Long
): SubtitleCue? {
    var low = 0
    var high = cues.size
    while (low < high) {
        val mid = low + (high - low) / 2
        if (cues[mid].startMs <= positionMs) {
            low = mid + 1
        } else {
            high = mid
        }
    }
    val index = low - 1
    if (index < 0) return null
    val cue = cues[index]
    return cue.takeIf { positionMs < it.endMs }
}

object SubtitleParser {
    fun parse(context: Context, uri: Uri): List<SubtitleCue> {
        val name = displayName(context, uri).lowercase(Locale.US)
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.US)
        return if (
            name.endsWith(".ttml") ||
            name.endsWith(".xml") ||
            mime == "application/ttml+xml"
        ) {
            parseTtml(context, uri)
        } else {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = readBounded(input)
                val encoding = when {
                    bytes.size >= 2 && bytes[0] == 0xFF.toByte() &&
                        bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
                    bytes.size >= 2 && bytes[0] == 0xFE.toByte() &&
                        bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
                    else -> Charsets.UTF_8
                }
                val decoded = String(bytes, encoding)
                if (encoding == Charsets.UTF_8 && '\uFFFD' in decoded) {
                    String(bytes, java.nio.charset.Charset.forName("windows-1252"))
                } else {
                    decoded
                }
            }.orEmpty().trimStart('\uFEFF')

            parsePlainText(name, mime, text)
        }
    }

    internal fun parsePlainText(
        name: String,
        mime: String,
        text: String
    ): List<SubtitleCue> {
        return when {
            name.endsWith(".ass") ||
                name.endsWith(".ssa") ||
                mime.contains("ssa") ||
                mime.contains("x-ass") ||
                text.lineSequence().any {
                    it.trimStart().startsWith("Dialogue:", ignoreCase = true)
                } -> parseAss(text)
            else -> parseSrtOrVtt(text)
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val maximum = 20 * 1024 * 1024
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val bytes = input.read(buffer)
            if (bytes < 0) break
            if (output.size() + bytes > maximum) {
                throw IOException("Subtitle file exceeds 20 MB")
            }
            output.write(buffer, 0, bytes)
        }
        return output.toByteArray()
    }

    private fun displayName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column)
                else null
            }
        }.getOrNull().orEmpty()
            .ifBlank { uri.lastPathSegment.orEmpty() }
    }

    private fun parseSrtOrVtt(input: String): List<SubtitleCue> {
        val normalized = input
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        return normalized.split(Regex("\n{2,}"))
            .mapNotNull { block ->
                val lines = block.lines().filter { it.isNotBlank() }
                val timingIndex = lines.indexOfFirst { it.contains("-->") }
                if (timingIndex < 0) return@mapNotNull null

                val timing = lines[timingIndex].split("-->")
                if (timing.size != 2) return@mapNotNull null

                val start = parseTime(timing[0].trim()) ?: return@mapNotNull null
                val endToken = timing[1].trim().substringBefore(' ')
                val end = parseTime(endToken) ?: return@mapNotNull null
                if (end <= start) return@mapNotNull null

                val body = lines.drop(timingIndex + 1)
                    .joinToString("\n")
                    .replace(Regex("<[^>]+>"), "")
                    .trim()
                if (body.isBlank()) null else SubtitleCue(start, end, body)
            }
            .sortedBy { it.startMs }
    }

    private fun parseAss(input: String): List<SubtitleCue> {
        return input.lineSequence()
            .mapNotNull { line ->
                if (!line.startsWith("Dialogue:", ignoreCase = true)) {
                    return@mapNotNull null
                }

                val fields = line.substringAfter(':')
                    .split(',', limit = 10)
                if (fields.size < 10) return@mapNotNull null

                val start = parseTime(fields[1].trim()) ?: return@mapNotNull null
                val end = parseTime(fields[2].trim()) ?: return@mapNotNull null
                if (end <= start) return@mapNotNull null

                val body = fields[9]
                    .replace("\\N", "\n")
                    .replace(Regex("\\{[^}]*}"), "")
                    .trim()
                if (body.isBlank()) null else SubtitleCue(start, end, body)
            }
            .sortedBy { it.startMs }
            .toList()
    }

    private fun parseTtml(context: Context, uri: Uri): List<SubtitleCue> {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val factory = DocumentBuilderFactory.newInstance().apply {
                    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                    setFeature(
                        "http://apache.org/xml/features/disallow-doctype-decl",
                        true
                    )
                    setFeature(
                        "http://xml.org/sax/features/external-general-entities",
                        false
                    )
                    setFeature(
                        "http://xml.org/sax/features/external-parameter-entities",
                        false
                    )
                    setFeature(
                        "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                        false
                    )
                    isXIncludeAware = false
                    setExpandEntityReferences(false)
                }
                val document = factory.newDocumentBuilder().parse(
                    ByteArrayInputStream(readBounded(input))
                )
                val nodes = document.getElementsByTagName("p")
                buildList {
                    for (index in 0 until nodes.length) {
                        val element = nodes.item(index) as? Element ?: continue
                        val start = parseTime(element.getAttribute("begin")) ?: continue
                        val end = parseTime(element.getAttribute("end"))
                            ?: element.getAttribute("dur")
                                .takeIf { it.isNotBlank() }
                                ?.let(::parseTime)
                                ?.let { start + it }
                            ?: continue
                        val body = element.textContent?.trim().orEmpty()
                        if (end > start && body.isNotBlank()) {
                            add(SubtitleCue(start, end, body))
                        }
                    }
                }.sortedBy { it.startMs }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun parseTime(token: String): Long? {
        val clean = token
            .trim()
            .replace(',', '.')
            .substringBefore(' ')

        if (clean.endsWith("ms")) {
            return clean.removeSuffix("ms").toDoubleOrNull()?.toLong()
        }
        if (clean.endsWith("s")) {
            return clean.removeSuffix("s").toDoubleOrNull()
                ?.let { (it * 1000.0).toLong() }
        }

        val parts = clean.split(':')
        return when (parts.size) {
            3 -> {
                val h = parts[0].toDoubleOrNull() ?: return null
                val m = parts[1].toDoubleOrNull() ?: return null
                val s = parts[2].toDoubleOrNull() ?: return null
                ((h * 3600 + m * 60 + s) * 1000.0).toLong()
            }
            2 -> {
                val m = parts[0].toDoubleOrNull() ?: return null
                val s = parts[1].toDoubleOrNull() ?: return null
                ((m * 60 + s) * 1000.0).toLong()
            }
            1 -> clean.toDoubleOrNull()?.let { (it * 1000.0).toLong() }
            else -> null
        }
    }
}
