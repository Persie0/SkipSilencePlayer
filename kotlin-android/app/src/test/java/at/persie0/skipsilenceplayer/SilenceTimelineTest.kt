package at.persie0.skipsilenceplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceTimelineTest {
    @Test
    fun invalidDurationOrMissingRangesHaveNoSpans() {
        val quiet = listOf(SilenceRangeMs(100, 200))
        assertTrue(timelineSilenceSpans(quiet, 0).isEmpty())
        assertTrue(timelineSilenceSpans(quiet, -1).isEmpty())
        assertTrue(timelineSilenceSpans(emptyList(), 10_000).isEmpty())
        assertTrue(timelineSilenceSpans(quiet, 10_000, 0).isEmpty())
    }

    @Test
    fun oneQuietSectionRetainsExpectedPosition() {
        val spans = timelineSilenceSpans(
            listOf(SilenceRangeMs(2_000, 4_000)), 10_000, buckets = 100
        )
        assertEquals(listOf(SilenceRangeMs(2_000, 4_000)), spans)
    }

    @Test
    fun manyRangesNeverExceedPixelBudgetAndRemainOrdered() {
        val ranges = (0 until 8_000).map {
            val t = it * 300L
            SilenceRangeMs(t, t + 120L)
        }
        val result = timelineSilenceSpans(
            ranges, durationMs = 2_400_000, buckets = 384
        )
        assertTrue(result.size <= 384)
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.startMs >= 0 && it.endMs <= 2_400_000 })
        assertTrue(result.zipWithNext().all { (left, right) ->
            left.endMs < right.startMs
        })
    }

    @Test
    fun clippingAndReversedIntervalsDoNotExceedMediaBounds() {
        val result = timelineSilenceSpans(
            listOf(
                SilenceRangeMs(-500, 250),
                SilenceRangeMs(500, 250),
                SilenceRangeMs(9_700, 12_000)
            ),
            durationMs = 10_000,
            buckets = 100
        )
        assertEquals(2, result.size)
        assertEquals(0L, result.first().startMs)
        assertEquals(10_000L, result.last().endMs)
    }
}
