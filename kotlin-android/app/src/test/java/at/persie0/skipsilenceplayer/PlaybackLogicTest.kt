package at.persie0.skipsilenceplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLogicTest {
    @Test
    fun queueCodecRoundTripsAndKeepsCurrentItem() {
        val raw = QueueCodec.encode(
            entries = listOf(
                QueueRecord("content://video/1", "one.mp4", "content://sub/1"),
                QueueRecord("content://video/2", "two.mkv", null)
            ),
            currentUri = "content://video/2"
        )

        val restored = QueueCodec.decode(raw)
        requireNotNull(restored)

        assertEquals(2, restored.entries.size)
        assertEquals("content://video/2", restored.currentUri)
        assertEquals("content://sub/1", restored.entries.first().subtitleUri)
        assertNull(restored.entries.last().subtitleUri)
    }

    @Test
    fun malformedQueueDoesNotCrash() {
        assertNull(QueueCodec.decode("{not-json"))
        assertNull(QueueCodec.decode(null))
    }

    @Test
    fun subtitleMatchingSupportsLanguageSuffixes() {
        assertTrue(subtitleMatchesVideo("lecture.mp4", "lecture.srt"))
        assertTrue(subtitleMatchesVideo("lecture.mp4", "lecture.en.srt"))
        assertTrue(subtitleMatchesVideo("lecture.mp4", "lecture.de-DE.ass"))
        assertFalse(subtitleMatchesVideo("lecture.mp4", "other.srt"))
    }

    @Test
    fun silenceAccumulatorAppliesMinimumAndPadding() {
        val accumulator = SilenceAccumulator(
            thresholdDb = -40f,
            minimumSilenceMs = 400,
            paddingMs = 50
        )

        accumulator.accept(0, 200, -10.0)
        accumulator.accept(200, 500, -55.0)
        accumulator.accept(500, 900, -55.0)
        accumulator.accept(900, 1100, -12.0)
        accumulator.accept(1100, 1300, -60.0)
        accumulator.accept(1300, 1400, -10.0)

        val ranges = accumulator.finish(1400)
        assertEquals(1, ranges.size)
        assertEquals(SilenceRangeMs(250, 850), ranges.first())
    }
}
