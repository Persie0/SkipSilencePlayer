package at.persie0.skipsilenceplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceSeekCoordinatorTest {
    @Test
    fun confirmedSeekCountsOnlyObservedSkippedTime() {
        val gate = SilenceSeekCoordinator()
        assertTrue(gate.propose("movie", 1000L, 2100L, 3000L))
        val saved = gate.onDiscontinuity(
            "movie", 1000L, 2090L, isSeek = true
        )
        assertEquals(1090L, saved)
        assertEquals(
            0L,
            gate.onDiscontinuity("movie", 2090L, 3000L, isSeek = true)
        )
    }

    @Test
    fun unsupportedOrSupersededSeekDoesNotCreditTime() {
        val gate = SilenceSeekCoordinator()
        assertTrue(gate.propose("movie", 1000L, 2000L, 3000L))
        assertEquals(
            0L, gate.onDiscontinuity("movie", 1000L, 1900L, isSeek = false)
        )
        assertFalse(gate.propose("movie", 1100L, 2000L, 3500L))
        assertTrue(gate.propose("movie", 1100L, 2000L, 4100L))
        assertEquals(
            0L, gate.onDiscontinuity("movie", 1100L, 3600L, isSeek = true)
        )
    }

    @Test
    fun pendingSeekPreventsRepeatedCommandsAndExpires() {
        val gate = SilenceSeekCoordinator()
        assertTrue(gate.propose("movie", 400L, 1700L, 1000L))
        assertFalse(gate.propose("movie", 410L, 1700L, 1200L))
        assertTrue(gate.propose("movie", 500L, 1700L, 2600L))
        assertEquals(1200L, gate.onDiscontinuity("movie", 500L, 1700L, true))
    }

    @Test
    fun mismatchedMediaAndManualSeekNeverCount() {
        val gate = SilenceSeekCoordinator()
        assertTrue(gate.propose("first", 1000L, 2000L, 1000L))
        assertEquals(0L, gate.onDiscontinuity("other", 1000L, 2000L, true))
        assertTrue(gate.propose("second", 1000L, 2000L, 1100L))
        gate.reset()
        assertEquals(0L, gate.onDiscontinuity("second", 1000L, 2000L, true))
    }

    @Test
    fun windowSizeBoundsLongVideoMemoryUse() {
        assertEquals(20, analysisWindowMs(0L))
        assertEquals(20, analysisWindowMs(3_600_000L))
        assertEquals(40, analysisWindowMs(18_000_000L))
        assertEquals(80, analysisWindowMs(36_000_000L))
        assertEquals(100, analysisWindowMs(200_000_000L))
    }

    @Test
    fun failedRequestNeverConfirmsAfterCancellation() {
        val gate = SilenceSeekCoordinator()
        assertTrue(gate.propose("movie", 3000L, 4200L, 1000L))
        gate.cancel()
        assertEquals(0L, gate.onDiscontinuity("movie", 3000L, 4200L, true))
    }

    @Test
    fun incompleteAnalysisUsesFallbackBeyondCoverage() {
        assertTrue(analyzedCoverageContains(5000L, 10000L, true, false))
        assertFalse(analyzedCoverageContains(9900L, 10000L, true, false))
        assertFalse(analyzedCoverageContains(10000L, 10000L, true, false))
        assertFalse(analyzedCoverageContains(100L, 0L, true, false))
        assertFalse(analyzedCoverageContains(100L, 10000L, false, false))
        assertTrue(analyzedCoverageContains(100000L, 10000L, true, true))
    }
}
