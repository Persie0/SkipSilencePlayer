package at.persie0.skipsilenceplayer

/**
 * Continuously accelerate the whole video timeline during verified quiet intervals.
 * Unlike an audio-only silence processor this preserves audio/video synchronisation.
 * All inputs are media-time milliseconds; callers restore normal speed on pauses,
 * manual seeks, unsupported/unanalysed sections and media changes.
 */
internal object SmoothPlaybackSpeed {
    fun target(
        baseSpeed: Float,
        quietMultiplier: Float,
        positionMs: Long,
        silentRanges: List<SilenceRangeMs>,
        active: Boolean
    ): Float {
        val normal = baseSpeed.coerceIn(0.5f, 3f)
        if (!active || positionMs < 0L) return normal

        val range = AdaptiveSilenceDetector.containingRange(silentRanges, positionMs)
            ?: return normal
        val rampMs = minOf(180f, range.durationMs / 3f).coerceAtLeast(1f)
        val distanceFromStart = (positionMs - range.startMs).toFloat()
        val distanceToEnd = (range.endMs - positionMs).toFloat()
        val blend = minOf(
            1f,
            distanceFromStart / rampMs,
            distanceToEnd / rampMs
        ).coerceIn(0f, 1f)
        val quietSpeed = (normal * quietMultiplier.coerceIn(2f, 8f))
            .coerceAtMost(8f)
        return normal + (quietSpeed - normal) * blend
    }
}
