package at.persie0.skipsilenceplayer

/**
 * SMOOTH uses Media3's audio processor to shorten quiet PCM without repeatedly
 * seeking the video decoder. This retains edge audio and volume ramps.
 *
 * JUMP preserves the old, more aggressive analyzed-timeline behavior for users
 * who prefer removing entire detected quiet spans. Outside analyzed coverage,
 * both modes use Media3's native processor instead of blind seeks.
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
    ): Boolean = skipEnabled && (
        mode == SilencePlaybackMode.SMOOTH || !analyzedCoverage
    )

    fun timelineEnabled(
        skipEnabled: Boolean,
        mode: SilencePlaybackMode,
        analyzedCoverage: Boolean
    ): Boolean = skipEnabled &&
        mode == SilencePlaybackMode.JUMP &&
        analyzedCoverage
}
