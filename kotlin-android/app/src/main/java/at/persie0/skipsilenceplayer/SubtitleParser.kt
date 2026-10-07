package at.persie0.skipsilenceplayer

import android.content.Context
import android.net.Uri
import org.w3c.dom.Element
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object SubtitleParser {
    fun parse(context: Context, uri: Uri): List<SubtitleCue> {
        val name = uri.lastPathSegment?.lowercase(Locale.US).orEmpty()
        return if (name.endsWith(".ttml") || name.endsWith(".xml")) {
            parseTtml(context, uri)
        } else {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }.orEmpty()

            when {
                name.endsWith(".ass") || name.endsWith(".ssa") -> parseAss(text)
                else -> parseSrtOrVtt(text)
            }
        }
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
                val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                val document = builder.parse(input)
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
