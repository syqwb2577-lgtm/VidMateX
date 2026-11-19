package app.vidmatex.player

import java.util.Locale

/**
 * Pure playback-speed policy for the Media3 preview player (§3). No Android or Media3
 * framework imports so the progression, clamping, and snapping rules can be exercised
 * by plain JVM unit tests. The controller applies the chosen rate through
 * `PlaybackParameters(playbackSpeed = …)`; this object only decides WHICH rate.
 *
 * Supported ladder mirrors common ExoPlayer UIs: 0.25x … 2x in quarter steps.
 */
object PlaybackSpeed {
    const val MIN_RATE = 0.25f
    const val MAX_RATE = 2.0f
    const val NORMAL_RATE = 1.0f

    /** Ordered, ascending supported speeds. The UI renders this list verbatim. */
    val LADDER: List<Float> = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    /** Clamp any requested rate into the supported [MIN_RATE]..[MAX_RATE] band. */
    fun clamp(rate: Float): Float = rate.coerceIn(MIN_RATE, MAX_RATE)

    /**
     * Snap [rate] to the nearest ladder entry. Ties (equal distance to two steps)
     * resolve toward the higher speed.
     *
     * Lexicographic comparison (distance primary, higher candidate secondary) is used
     * instead of a weighted scalar score: a scalar `distance * k - candidate` with any
     * finite k lets the tie-break term overwhelm true distance near every midpoint
     * (e.g. 0.35 wrongly snapped to 0.5x instead of the nearer 0.25x).
     */
    fun nearestSupported(rate: Float): Float {
        val clamped = clamp(rate)
        return LADDER.minWithOrNull(
            compareBy(
                { candidate -> kotlin.math.abs(candidate - clamped) },
                { candidate -> -candidate }, // tie-break: higher speed wins
            )
        ) ?: NORMAL_RATE
    }

    /** Next speed strictly greater than the current one; stays at [MAX_RATE] at the top. */
    fun stepUp(rate: Float): Float {
        val current = nearestSupported(rate)
        return LADDER.firstOrNull { it > current + 0.0001f } ?: MAX_RATE
    }

    /** Next speed strictly below the current one; stays at [MIN_RATE] at the bottom. */
    fun stepDown(rate: Float): Float {
        val current = nearestSupported(rate)
        return LADDER.lastOrNull { it < current - 0.0001f } ?: MIN_RATE
    }

    /** True when [rate] exactly equals a ladder entry (within float tolerance). */
    fun isSupported(rate: Float): Boolean =
        LADDER.any { kotlin.math.abs(it - rate) < 0.0001f }

    /** Stable menu label, e.g. `0.5x`, `1.25x`; normal speed renders as `1x`. */
    fun label(rate: Float): String {
        val snapped = nearestSupported(rate)
        if (kotlin.math.abs(snapped - 1.0f) < 0.0001f) return "1x"
        val trimmed = String.format(Locale.US, "%.2f", snapped).trimEnd('0').trimEnd('.')
        return "${trimmed}x"
    }
}
