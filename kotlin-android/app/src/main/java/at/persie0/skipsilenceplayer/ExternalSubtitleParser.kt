package at.persie0.skipsilenceplayer

import android.content.Context
import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

internal data class TimedSubtitle(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

internal object ExternalSubtitleParser {
    suspend fun parse(
        context: Context,
        uri: Uri
    ): List<TimedSubtitle> = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: return@withContext emptyList()

        val path = uri.lastPathSegment.orEmpty().lowercase()
        when {
            path.endsWith(".ass") || path.endsWith(".ssa") -> parseAss(text)
            path.endsWith(".ttml") || path.endsWith(".xml") -> parseTtml(text)
            else -> parseSrtOrVtt(text)
        }
    }

    fun cueAt(
        cues: List<TimedSubtitle>,
        playbackPositionMs: Long,
        offsetMs: Long
    ): String {
        return cues.firstOrNull {
            val start = it.startMs + offsetMs
            val end = it.endMs + offsetMs
            playbackPositionMs in start until end
        }?.text.orEmpty()
    }

    private fun parseSrtOrVtt(input: String): List<TimedSubtitle> {
        val normalized = input
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        return normalized
            .split(Regex("\n\s*\n"))
            .mapNotNull { block ->
                val lines = block.lines()
                    .map { it.trimEnd() }
                    .filter { it.isNotBlank() }

                val timingIndex = lines.indexOfFirst { "-->" in it }
                if (timingIndex < 0) return@mapNotNull null

                val timing = lines[timingIndex].split("-->")
                if (timing.size != 2) return@mapNotNull null

                val start = parseTime(timing[0].trim())
                    ?: return@mapNotNull null
                val endToken = timing[1].trim().substringBefore(' ')
                val end = parseTime(endToken)
                    ?: return@mapNotNull null
                if (end <= start) return@mapNotNull null

                val body = lines
                    .drop(timingIndex + 1)
                    .joinToString("\n")
                    .replace(Regex("<[^>]+>"), "")
                    .trim()

                if (body.isBlank()) null
                else TimedSubtitle(start, end, body)
            }
            .sortedBy { it.startMs }
    }

    private fun parseAss(input: String): List<TimedSubtitle> {
        return input.lineSequence()
            .mapNotNull { line ->
                if (!line.startsWith("Dialogue:", ignoreCase = true)) {
                    return@mapNotNull null
                }

                val fields = line
                    .substringAfter(':')
                    .split(',', limit = 10)

                if (fields.size < 10) return@mapNotNull null

                val start = parseTime(fields[1]) ?: return@mapNotNull null
                val end = parseTime(fields[2]) ?: return@mapNotNull null
                if (end <= start) return@mapNotNull null

                val body = fields[9]
                    .replace("\\N", "\n")
                    .replace(Regex("\\{[^}]*}"), "")
                    .trim()

                if (body.isBlank()) null
                else TimedSubtitle(start, end, body)
            }
            .sortedBy { it.startMs }
            .toList()
    }

    private fun parseTtml(input: String): List<TimedSubtitle> {
        val parser = Xml.newPullParser()
        parser.setInput(input.reader())

        val cues = mutableListOf<TimedSubtitle>()
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            if (
                event == XmlPullParser.START_TAG &&
                parser.name.equals("p", ignoreCase = true)
            ) {
                val begin = parser.getAttributeValue(null, "begin")
                val end = parser.getAttributeValue(null, "end")
                val duration = parser.getAttributeValue(null, "dur")
                val startMs = begin?.let(::parseTime)

                if (startMs != null) {
                    val endMs = end?.let(::parseTime)
                        ?: duration?.let(::parseTime)?.let { startMs + it }

                    if (endMs != null && endMs > startMs) {
                        val body = parser.nextText()
                            .replace(Regex("\s+"), " ")
                            .trim()

                        if (body.isNotBlank()) {
                            cues += TimedSubtitle(startMs, endMs, body)
                        }
                    }
                }
            }
            event = parser.next()
        }

        return cues.sortedBy { it.startMs }
    }

    private fun parseTime(raw: String): Long? {
        val clean = raw.trim().replace(',', '.')

        if (clean.endsWith("ms", ignoreCase = true)) {
            return clean.dropLast(2).toDoubleOrNull()?.toLong()
        }
        if (clean.endsWith("s", ignoreCase = true)) {
            return clean.dropLast(1).toDoubleOrNull()
                ?.times(1000.0)
                ?.toLong()
        }

        val parts = clean.split(':')
        return when (parts.size) {
            3 -> {
                val hours = parts[0].toDoubleOrNull() ?: return null
                val minutes = parts[1].toDoubleOrNull() ?: return null
                val seconds = parts[2].toDoubleOrNull() ?: return null
                ((hours * 3600 + minutes * 60 + seconds) * 1000.0).toLong()
            }

            2 -> {
                val minutes = parts[0].toDoubleOrNull() ?: return null
                val seconds = parts[1].toDoubleOrNull() ?: return null
                ((minutes * 60 + seconds) * 1000.0).toLong()
            }

            else -> clean.toDoubleOrNull()?.times(1000.0)?.toLong()
        }
    }
}
