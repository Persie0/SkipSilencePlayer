package at.persie0.skipsilenceplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilencePlaybackModeTest {
    @Test
    fun smoothNeverUsesDecoderSeekEvenWithFullAnalysis() {
        assertTrue(SilencePlaybackPolicy.nativeEnabled(true, SilencePlaybackMode.SMOOTH, true))
        assertTrue(SilencePlaybackPolicy.nativeEnabled(true, SilencePlaybackMode.SMOOTH, false))
        assertFalse(SilencePlaybackPolicy.timelineEnabled(true, SilencePlaybackMode.SMOOTH, true))
        assertFalse(SilencePlaybackPolicy.timelineEnabled(true, SilencePlaybackMode.SMOOTH, false))
    }

    @Test
    fun jumpModeUsesOnlyOneSkippingMechanismPerCoverageArea() {
        assertFalse(SilencePlaybackPolicy.nativeEnabled(true, SilencePlaybackMode.JUMP, true))
        assertTrue(SilencePlaybackPolicy.timelineEnabled(true, SilencePlaybackMode.JUMP, true))
        assertTrue(SilencePlaybackPolicy.nativeEnabled(true, SilencePlaybackMode.JUMP, false))
        assertFalse(SilencePlaybackPolicy.timelineEnabled(true, SilencePlaybackMode.JUMP, false))
    }

    @Test
    fun disabledModeNeverSkips() {
        for (mode in SilencePlaybackMode.entries) {
            for (coverage in listOf(true, false)) {
                assertFalse(SilencePlaybackPolicy.nativeEnabled(false, mode, coverage))
                assertFalse(SilencePlaybackPolicy.timelineEnabled(false, mode, coverage))
            }
        }
    }

    @Test
    fun upgradeAndUnknownPreferenceDefaultToSmooth() {
        assertEquals(SilencePlaybackMode.SMOOTH, SilencePlaybackMode.fromPreference(null))
        assertEquals(SilencePlaybackMode.SMOOTH, SilencePlaybackMode.fromPreference("unknown"))
        assertEquals(SilencePlaybackMode.SMOOTH, SilencePlaybackMode.fromPreference("SMOOTH"))
        assertEquals(SilencePlaybackMode.JUMP, SilencePlaybackMode.fromPreference("JUMP"))
    }
}
