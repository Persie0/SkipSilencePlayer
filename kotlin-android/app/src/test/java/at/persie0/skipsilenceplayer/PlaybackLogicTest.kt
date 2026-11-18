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
    fun subtitleParserRecognizesAssDespiteOpaqueDocumentName() {
        val ass = """
            [Script Info]
            Title: Sample
            [Events]
            Dialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,Hello\\Nworld
        """.trimIndent()

        val cues = SubtitleParser.parsePlainText(
            name = "msf:713",
            mime = "application/octet-stream",
            text = ass
        )
        assertEquals(1, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(2500L, cues[0].endMs)
        assertTrue(cues[0].text.contains("Hello"))
    }

    @Test
    fun subtitleParserStillRecognizesStandardSrt() {
        val srt = "1\\n00:00:01,250 --> 00:00:02,000\\nExample\\n"
            .replace("\\n", "\n")
        val cues = SubtitleParser.parsePlainText(
            name = "clip.srt",
            mime = "text/plain",
            text = srt
        )
        assertEquals(1, cues.size)
        assertEquals(1250L, cues[0].startMs)
    }

    @Test
    fun activeSubtitleCueUsesCorrectBoundaries() {
        val cues = listOf(
            SubtitleCue(1000L, 1900L, "First"),
            SubtitleCue(5000L, 6000L, "Second"),
            SubtitleCue(9000L, 9800L, "Third")
        )

        assertNull(activeSubtitleCue(cues, 999L))
        assertEquals("First", activeSubtitleCue(cues, 1000L)?.text)
        assertNull(activeSubtitleCue(cues, 1900L))
        assertNull(activeSubtitleCue(cues, 4900L))
        assertEquals("Second", activeSubtitleCue(cues, 5500L)?.text)
        assertEquals("Third", activeSubtitleCue(cues, 9000L)?.text)
        assertNull(activeSubtitleCue(cues, 9800L))
        assertNull(activeSubtitleCue(emptyList(), 2000L))
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
