package at.persie0.skipsilenceplayer

import org.json.JSONArray
import org.json.JSONObject

data class QueueRecord(
    val uri: String,
    val name: String,
    val subtitleUri: String? = null
)

data class RestoredQueue(
    val entries: List<QueueRecord>,
    val currentUri: String?
)

object QueueCodec {
    fun encode(entries: List<QueueRecord>, currentUri: String?): String {
        val root = JSONObject()
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("uri", entry.uri)
                    .put("name", entry.name)
                    .put("subtitleUri", entry.subtitleUri)
            )
        }
        root.put("entries", array)
        root.put("currentUri", currentUri)
        return root.toString()
    }

    fun decode(raw: String?): RestoredQueue? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray("entries") ?: return@runCatching null
            val entries = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val uri = item.optString("uri")
                    if (uri.isBlank()) continue
                    add(
                        QueueRecord(
                            uri = uri,
                            name = item.optString("name", "Video"),
                            subtitleUri = item.optString("subtitleUri")
                                .takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }
            RestoredQueue(
                entries = entries,
                currentUri = root.optString("currentUri")
                    .takeIf { it.isNotBlank() && it != "null" }
            )
        }.getOrNull()
    }
}

fun subtitleMatchesVideo(videoName: String, subtitleName: String): Boolean {
    val videoBase = videoName.substringBeforeLast('.', videoName).lowercase()
    val subtitleBase = subtitleName.substringBeforeLast('.', subtitleName).lowercase()
    return subtitleBase == videoBase || subtitleBase.startsWith("$videoBase.")
}
