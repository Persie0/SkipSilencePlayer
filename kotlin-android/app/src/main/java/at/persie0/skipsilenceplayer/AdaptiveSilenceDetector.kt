package at.persie0.skipsilenceplayer

import kotlin.math.max
import kotlin.math.min

/** One timestamped 20ms (approximately) RMS measurement of decoded PCM. */
data class AudioLoudnessWindow(
    val startMs: Long,
    val endMs: Long,
    val db: Double
)

data class SilenceDetectionResult(
    val ranges: List<SilenceRangeMs>,
    val thresholdDb: Float,
    val noiseFloorDb: Float,
    val adaptive: Boolean
)

/**
 * Conservative, per-file noise-floor calibration.
 *
 * Manual mode uses exactly the selected dB threshold. Adaptive mode uses a low
 * percentile as the floor, but never exceeds the upper speech distribution
 * minus a 12 dB safety margin. It intentionally prefers missing ambiguous
 * silence to cutting quiet speech.
 */
object AdaptiveSilenceDetector {
    fun detect(
        windows: List<AudioLoudnessWindow>,
        manualThresholdDb: Float,
        minimumSilenceMs: Long,
        paddingMs: Long,
        adaptive: Boolean
    ): SilenceDetectionResult {
        if (windows.isEmpty()) {
            return SilenceDetectionResult(
                emptyList(), manualThresholdDb, -100f, adaptive
            )
        }

        val sorted = windows.map { sample ->
            if (sample.db.isNaN()) 0.0 else sample.db.coerceIn(-100.0, 0.0)
        }.sorted()

        fun percentile(p: Double): Double {
            val index = ((sorted.lastIndex) * p).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }

        val digitalShare = sorted.count { it <= -80.0 }.toDouble() / sorted.size
        val audible = sorted.filter { it > -80.0 }
        val audibleFloor = if (audible.isNotEmpty()) {
            audible[(audible.lastIndex * 0.25).toInt()]
        } else {
            -100.0
        }
        val floor = if (digitalShare > 0.10 && audible.size >= 10) {
            audibleFloor
        } else {
            percentile(0.20)
        }
        val upper = percentile(0.95)
        val speechCap = if (upper - floor >= 14.0) {
            upper - 12.0
        } else {
            floor - 6.0 // No clear separation: never classify soft speech as silence.
        }

        val threshold = if (adaptive) {
            min(
                max(manualThresholdDb.toDouble() - 10.0, floor + 8.0),
                speechCap
            ).coerceIn(-60.0, -20.0).toFloat()
        } else {
            manualThresholdDb.coerceIn(-60f, -20f)
        }

        val minimum = minimumSilenceMs.coerceAtLeast(100L)
        val padding = paddingMs.coerceAtLeast(0L)
        val ranges = mutableListOf<SilenceRangeMs>()
        var quietStart: Long? = null
        var quietEnd = 0L

        fun close() {
            val start = quietStart ?: return
            val end = quietEnd
            if (end - start >= minimum && end - start > 2 * padding + 80L) {
                ranges += SilenceRangeMs(start + padding, end - padding)
            }
            quietStart = null
        }

        for (sample in windows) {
            val start = sample.startMs
            val end = sample.endMs
            if (start < 0 || end <= start) continue

            if (quietStart != null && start > quietEnd + 60L) {
                close() // Never skip a gap that the decoder did not observe.
            }

            if (sample.db <= threshold) {
                if (quietStart == null) quietStart = start
                quietEnd = max(quietEnd, end)
            } else {
                close()
            }
        }
        close()

        return SilenceDetectionResult(
            ranges = ranges,
            thresholdDb = threshold,
            noiseFloorDb = floor.toFloat(),
            adaptive = adaptive
        )
    }

    /** Binary search so polling does not scan tens of thousands of ranges. */
    fun containingRange(ranges: List<SilenceRangeMs>, positionMs: Long): SilenceRangeMs? {
        var low = 0
        var high = ranges.lastIndex
        while (low <= high) {
            val mid = (low + high).ushr(1)
            val current = ranges[mid]
            when {
                positionMs < current.startMs -> high = mid - 1
                positionMs >= current.endMs -> low = mid + 1
                else -> return current
            }
        }
        return null
    }
}
