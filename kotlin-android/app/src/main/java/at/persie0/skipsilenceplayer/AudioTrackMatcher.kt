package at.persie0.skipsilenceplayer

import java.util.Locale

internal data class AudioStreamCandidate(
    val index: Int,
    val language: String?
)

/** Returns null when selecting a track by language is ambiguous. */
internal fun chooseAudioStreamIndex(
    candidates: List<AudioStreamCandidate>,
    desiredLanguage: String?
): Int? {
    if (candidates.size == 1) return candidates.first().index
    if (candidates.isEmpty()) return null

    val language = normalizeAudioLanguage(desiredLanguage) ?: return null
    val matching = candidates.filter {
        normalizeAudioLanguage(it.language) == language
    }
    return matching.singleOrNull()?.index
}

private fun normalizeAudioLanguage(language: String?): String? {
    val value = language
        ?.trim()
        ?.replace('_', '-')
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val locale = Locale.forLanguageTag(value)
    val code = runCatching { locale.isO3Language }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: locale.language
    return code.takeIf { it.isNotBlank() && it != "und" }
}
