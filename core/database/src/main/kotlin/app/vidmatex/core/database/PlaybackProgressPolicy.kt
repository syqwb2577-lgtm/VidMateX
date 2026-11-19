package app.vidmatex.core.database

/**
 * Pure-JVM playback-position rules (no Room/Android calls): percent clamping,
 * completion threshold, and save-throttle decisions shared by the repository and
 * player layers. Room DAO persistence itself is device/instrumented.
 */
object PlaybackProgressPolicy {
    /** Fraction of duration at/after which a position counts as finished. */
    const val COMPLETION_THRESHOLD_PERCENT = 95
    /** Minimum milliseconds between position saves to avoid write churn. */
    const val MIN_SAVE_DELTA_MS = 5_000L

    fun clampPercent(percent: Int): Int = percent.coerceIn(0, 100)

    /** 0..1 fraction; unknown/zero duration yields 0.0 (never NaN or >1). */
    fun positionFraction(positionMs: Long, durationMs: Long): Double {
        if (durationMs <= 0L || positionMs <= 0L) return 0.0
        return (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
    }

    fun isComplete(positionMs: Long, durationMs: Long, thresholdPercent: Int = COMPLETION_THRESHOLD_PERCENT): Boolean {
        if (durationMs <= 0L) return false
        val threshold = thresholdPercent.coerceIn(50, 100)
        return positionFraction(positionMs, durationMs) * 100.0 >= threshold
    }

    /** Save when the position advanced past the throttle delta or playback reached completion. */
    fun shouldSave(currentMs: Long, lastSavedMs: Long, durationMs: Long): Boolean =
        currentMs - lastSavedMs >= MIN_SAVE_DELTA_MS || isComplete(currentMs, durationMs)
}
