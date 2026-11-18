package at.persie0.skipsilenceplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSilenceDetectorTest {
    private fun section(
        startMs: Long,
        endMs: Long,
        db: Double
    ): List<AudioLoudnessWindow> =
        (startMs until endMs step 20).map { start ->
            AudioLoudnessWindow(start, minOf(start + 20, endMs), db)
        }

    @Test
    fun adaptiveDetectsNoisyPausesThatFixedThresholdMisses() {
        val windows = section(0, 1000, -12.0) +
            section(1000, 2300, -36.0) +
            section(2300, 3300, -12.0) +
            section(3300, 4500, -36.0) +
            section(4500, 5500, -12.0)

        val fixed = AdaptiveSilenceDetector.detect(
            windows, -48f, 450, 80, adaptive = false
        )
        val adaptive = AdaptiveSilenceDetector.detect(
            windows, -48f, 450, 80, adaptive = true
        )

        assertTrue(fixed.ranges.isEmpty())
        assertEquals(2, adaptive.ranges.size)
        assertTrue(adaptive.thresholdDb > -36f)
        assertTrue(adaptive.thresholdDb < -20f)
        assertEquals(SilenceRangeMs(1080, 2220), adaptive.ranges[0])
        assertEquals(SilenceRangeMs(3380, 4420), adaptive.ranges[1])
    }

    @Test
    fun adaptiveProtectsUniformLowVolumeSpeech() {
        val speech = section(0, 4000, -54.0)
        val result = AdaptiveSilenceDetector.detect(
            speech, -42f, 450, 80, adaptive = true
        )
        assertTrue(result.ranges.isEmpty())
        assertTrue(result.thresholdDb < -54f)
    }

    @Test
    fun adaptiveStillSkipsDigitalSilenceBetweenSpeech() {
        val windows = section(0, 1000, -12.0) +
            section(1000, 2300, -100.0) +
            section(2300, 3300, -12.0)

        val result = AdaptiveSilenceDetector.detect(
            windows, -42f, 450, 80, adaptive = true
        )
        assertEquals(1, result.ranges.size)
        assertEquals(SilenceRangeMs(1080, 2220), result.ranges[0])
    }

    @Test
    fun detectorDoesNotBridgeMissingAudioSamples() {
        val windows = section(0, 1000, -90.0) +
            section(2000, 3000, -90.0) +
            section(3000, 4000, -12.0)
        val result = AdaptiveSilenceDetector.detect(
            windows, -42f, 450, 80, adaptive = false
        )
        assertEquals(2, result.ranges.size)
        assertEquals(SilenceRangeMs(80, 920), result.ranges[0])
        assertEquals(SilenceRangeMs(2080, 2920), result.ranges[1])
    }

    @Test
    fun seekRangeLookupIsBoundarySafe() {
        val ranges = listOf(
            SilenceRangeMs(1080, 2220),
            SilenceRangeMs(3380, 4420)
        )
        assertNull(AdaptiveSilenceDetector.containingRange(ranges, 1079))
        assertEquals(
            ranges[0],
            AdaptiveSilenceDetector.containingRange(ranges, 1080)
        )
        assertEquals(
            ranges[1],
            AdaptiveSilenceDetector.containingRange(ranges, 3500)
        )
        assertNull(AdaptiveSilenceDetector.containingRange(ranges, 4420))
    }

    @Test
    fun manualThresholdRemainsExact() {
        val windows = section(0, 1000, -39.0) +
            section(1000, 2200, -30.0)
        val result = AdaptiveSilenceDetector.detect(
            windows, -35f, 450, 80, adaptive = false
        )
        assertEquals(-35f, result.thresholdDb, 0.01f)
        assertFalse(result.ranges.isEmpty())
    }
}
