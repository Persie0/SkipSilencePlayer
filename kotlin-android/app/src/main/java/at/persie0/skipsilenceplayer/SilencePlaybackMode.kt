package at.persie0.skipsilenceplayer

/**
 * SMOOTH accelerates the full media timeline over analyzer-confirmed quiet sections,
 * with gentle rate ramps; it never skips only the audio or seeks the video.
 *
 * JUMP preserves the old, more aggressive analyzed-timeline behavior for users
 * who prefer removing entire detected quiet spans. Outside analyzed coverage,
 * JUMP may use native audio-only fallback if the timeline is not yet analyzed.
 */
enum class SilencePlaybackMode {
    SMOOTH,
    JUMP;

    companion object {
        fun fromPreference(value: String?): SilencePlaybackMode =
            entries.firstOrNull { it.name == value } ?: SMOOTH
    }
}

/** Pure routing decisions, independently testable from Android/Media3. */
internal object SilencePlaybackPolicy {
    fun nativeEnabled(
        skipEnabled: Boolean,
        mode: SilencePlaybackMode,
        analyzedCoverage: Boolean
    ): Boolean = skipEnabled &&
        mode == SilencePlaybackMode.JUMP &&
        !analyzedCoverage

    fun timelineEnabled(
        skipEnabled: Boolean,
        mode: SilencePlaybackMode,
        analyzedCoverage: Boolean
    ): Boolean = skipEnabled &&
        mode == SilencePlaybackMode.JUMP &&
        analyzedCoverage
}
