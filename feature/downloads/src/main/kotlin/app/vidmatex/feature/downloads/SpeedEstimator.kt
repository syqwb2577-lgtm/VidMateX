package app.vidmatex.feature.downloads

/**
 * Pure, Android-free smoothed transfer-speed estimator (exponential moving average) plus
 * the pure ETA math used by the worker progress state.
 *
 * The worker feeds cumulative downloaded bytes and `System.currentTimeMillis()` on each
 * progress tick; [update] returns the smoothed bytes/second value used for DAO state,
 * WorkManager progress data, and the notification. The ETA formula lives here so it can be
 * unit-tested without the worker.
 */
internal class SpeedEstimator(
    /** EMA weight of the newest instantaneous sample; 0 < alpha <= 1. */
    private val alpha: Double = 0.35,
) {
    private var lastBytes: Long = -1L
    private var lastMs: Long = -1L
    private var smoothedBps: Long = 0L

    /**
     * Records a cumulative byte sample and returns the smoothed speed (bytes/second).
     * The first sample yields 0 (no interval yet); negative deltas (restart from zero)
     * reset the baseline and yield 0 until the next tick.
     */
    fun update(cumulativeBytes: Long, nowMs: Long): Long {
        if (lastMs < 0L) {
            lastBytes = cumulativeBytes
            lastMs = nowMs
            return 0L
        }
        val dt = (nowMs - lastMs).coerceAtLeast(1L)
        val delta = cumulativeBytes - lastBytes
        if (delta < 0L) {
            // Server ignored Range and restarted the body: rebaseline the speed window.
            lastBytes = cumulativeBytes
            lastMs = nowMs
            smoothedBps = 0L
            return 0L
        }
        val instantBps = delta * 1000.0 / dt
        smoothedBps = (alpha * instantBps + (1.0 - alpha) * smoothedBps).toLong()
        lastBytes = cumulativeBytes
        lastMs = nowMs
        return smoothedBps
    }

    companion object {
        /**
         * Remaining-time estimate in whole seconds. Null when speed is zero/unknown or
         * there are no remaining bytes; a non-positive ETA collapses to null so the UI
         * never renders a negative countdown.
         */
        fun etaSeconds(remainingBytes: Long, speedBps: Long): Long? {
            if (speedBps <= 0L || remainingBytes <= 0L) return null
            return (remainingBytes / speedBps).coerceAtLeast(0L)
        }
    }
}
