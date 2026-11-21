package at.persie0.skipsilenceplayer

import kotlin.math.ceil

/**
 * Reduces potentially thousands of decoded silence intervals to a bounded
 * number of visual spans. At a normal phone width one bucket is about a
 * pixel; nearby spans may visually merge, but playback rules and seeking
 * always use the original exact timestamps.
 */
internal fun timelineSilenceSpans(
    ranges: List<SilenceRangeMs>,
    durationMs: Long,
    buckets: Int = 384
): List<SilenceRangeMs> {
    if (durationMs <= 0 || buckets <= 0 || ranges.isEmpty()) return emptyList()
    val filled = BooleanArray(buckets)
    val total = durationMs.toDouble()
    for (range in ranges) {
        val from = range.startMs.coerceIn(0L, durationMs)
        val to = range.endMs.coerceIn(0L, durationMs)
        if (to <= from) continue
        val first = ((from / total) * buckets).toInt()
            .coerceIn(0, buckets - 1)
        val afterLast = ceil((to / total) * buckets).toInt()
            .coerceIn(first + 1, buckets)
        for (index in first until afterLast) filled[index] = true
    }

    val spans = ArrayList<SilenceRangeMs>()
    var first = -1
    for (index in 0..buckets) {
        val occupied = index < buckets && filled[index]
        if (occupied && first == -1) first = index
        if (!occupied && first != -1) {
            val from = (first / buckets.toDouble() * total).toLong()
            val to = ceil(index / buckets.toDouble() * total)
                .toLong().coerceAtMost(durationMs)
            if (to > from) spans += SilenceRangeMs(from, to)
            first = -1
        }
    }
    return spans
}
