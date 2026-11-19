package app.vidmatex.feature.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for [SpeedEstimator] smoothed speed and ETA math (requirement §5).
 * No Android imports. Source-ready pending CI.
 */
class SpeedEstimatorTest {

    @Test
    fun `first sample yields zero speed (no interval yet)`() {
        val estimator = SpeedEstimator()
        assertEquals(0L, estimator.update(0L, 1_000L))
    }

    @Test
    fun `speed is exponential moving average of instantaneous samples`() {
        val estimator = SpeedEstimator(alpha = 0.35)
        estimator.update(0L, 1_000L)
        // 1000 bytes over 1000 ms => 1000 bps instant; smoothed = 0.35 * 1000.
        assertEquals(350L, estimator.update(1_000L, 2_000L))
        // Second identical sample: 0.35*1000 + 0.65*350 = 577.5 -> 577.
        assertEquals(577L, estimator.update(2_000L, 3_000L))
    }

    @Test
    fun `negative byte delta (server restarted body) rebaselines and yields zero`() {
        val estimator = SpeedEstimator()
        estimator.update(5_000L, 1_000L)
        estimator.update(6_000L, 2_000L)
        assertEquals(0L, estimator.update(1_000L, 3_000L))
    }

    @Test
    fun `eta requires positive remaining bytes and speed`() {
        assertEquals(20L, SpeedEstimator.etaSeconds(10_000L, 500L))
        assertNull(SpeedEstimator.etaSeconds(10_000L, 0L))
        assertNull(SpeedEstimator.etaSeconds(0L, 500L))
        assertNull(SpeedEstimator.etaSeconds(-5L, 500L))
        assertNull(SpeedEstimator.etaSeconds(10_000L, -10L))
    }
}
