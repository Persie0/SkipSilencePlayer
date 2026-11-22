package at.persie0.skipsilenceplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class SmoothPlaybackSpeedTest {
    private val quiet = listOf(SilenceRangeMs(1_000L, 3_000L))

    @Test fun quietSectionAcceleratesVideoAndAudioTogether() {
        assertEquals(4f, SmoothPlaybackSpeed.target(1f, 4f, 2_000L, quiet, true), 0.01f)
        assertEquals(1f, SmoothPlaybackSpeed.target(1f, 4f, 500L, quiet, true), 0.01f)
        assertEquals(1f, SmoothPlaybackSpeed.target(1f, 4f, 3_000L, quiet, true), 0.01f)
    }

    @Test fun transitionsAreRampedAtBothEdges() {
        assertEquals(1f, SmoothPlaybackSpeed.target(1f, 4f, 1_000L, quiet, true), 0.01f)
        val entering = SmoothPlaybackSpeed.target(1f, 4f, 1_090L, quiet, true)
        val leaving = SmoothPlaybackSpeed.target(1f, 4f, 2_910L, quiet, true)
        assertEquals(2.5f, entering, 0.01f)
        assertEquals(entering, leaving, 0.01f)
    }

    @Test fun respectsBaseSpeedAndHardwareSafeCap() {
        assertEquals(8f, SmoothPlaybackSpeed.target(2f, 8f, 2_000L, quiet, true), 0.01f)
        assertEquals(2f, SmoothPlaybackSpeed.target(2f, 8f, 2_000L, quiet, false), 0.01f)
        assertEquals(2f, SmoothPlaybackSpeed.target(2f, 8f, 5_000L, quiet, true), 0.01f)
    }

    @Test fun emptyOrUnanalysedRangesKeepRegularSpeed() {
        assertEquals(1.25f, SmoothPlaybackSpeed.target(1.25f, 5f, 2_000L, emptyList(), true), 0.01f)
        assertEquals(1.25f, SmoothPlaybackSpeed.target(1.25f, 5f, 2_000L, quiet, false), 0.01f)
    }
}
