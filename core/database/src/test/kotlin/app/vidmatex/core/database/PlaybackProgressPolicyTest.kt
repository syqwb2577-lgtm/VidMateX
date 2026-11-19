package app.vidmatex.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [PlaybackProgressPolicy] (DAO persistence itself is device-side). */
class PlaybackProgressPolicyTest {

    @Test fun `percent clamps into 0 100`() {
        assertEquals(0, PlaybackProgressPolicy.clampPercent(-10))
        assertEquals(100, PlaybackProgressPolicy.clampPercent(250))
        assertEquals(42, PlaybackProgressPolicy.clampPercent(42))
    }

    @Test fun `fraction guards against missing duration`() {
        assertEquals(0.0, PlaybackProgressPolicy.positionFraction(5_000, 0), 0.0001)
        assertEquals(0.0, PlaybackProgressPolicy.positionFraction(-1, 10_000), 0.0001)
        assertEquals(0.5, PlaybackProgressPolicy.positionFraction(5_000, 10_000), 0.0001)
        assertEquals(1.0, PlaybackProgressPolicy.positionFraction(99_999, 10_000), 0.0001)
    }

    @Test fun `completion threshold is 95 percent`() {
        assertTrue(PlaybackProgressPolicy.isComplete(9_600, 10_000))
        assertFalse(PlaybackProgressPolicy.isComplete(5_000, 10_000))
        assertFalse(PlaybackProgressPolicy.isComplete(1_000, 0))
    }

    @Test fun `save throttle skips tiny deltas but keeps completion saves`() {
        assertTrue(PlaybackProgressPolicy.shouldSave(15_000, 9_000, 20_000))
        assertFalse(PlaybackProgressPolicy.shouldSave(9_500, 9_000, 20_000))
        assertTrue(PlaybackProgressPolicy.shouldSave(19_900, 19_000, 20_000))
    }

    @Test fun `completion threshold clamps between 50 and 100`() {
        // threshold=0 is clamped to 50, so 40% never counts as complete.
        assertFalse(PlaybackProgressPolicy.isComplete(4_000, 10_000, thresholdPercent = 0))
        // threshold=150 is clamped to 100, so 99% is not complete.
        assertFalse(PlaybackProgressPolicy.isComplete(9_900, 10_000, thresholdPercent = 150))
        // Exactly at 100% with clamped threshold counts.
        assertTrue(PlaybackProgressPolicy.isComplete(10_000, 10_000, thresholdPercent = 150))
    }

    @Test fun `positionFraction never returns NaN or exceeds 1`() {
        val f = PlaybackProgressPolicy.positionFraction(Long.MAX_VALUE, 1L)
        assertTrue(f.isFinite())
        assertTrue(f <= 1.0)
        assertEquals(0.0, PlaybackProgressPolicy.positionFraction(0, 10_000), 0.0001)
    }

    @Test fun `shouldSave returns true exactly at throttle delta boundary`() {
        val delta = PlaybackProgressPolicy.MIN_SAVE_DELTA_MS
        assertTrue(PlaybackProgressPolicy.shouldSave(delta, 0L, 60_000L))
        assertFalse(PlaybackProgressPolicy.shouldSave(delta - 1, 0L, 60_000L))
    }
}
