package app.vidmatex.player

/**
 * Pure playback-volume policy for the preview player (§3). No Android/Media3 imports so
 * the clamp, percent, and mute rules run under plain JUnit. The controller applies the
 * chosen value through `ExoPlayer.volume`; this object decides WHICH value is legal.
 *
 * Media3 `Player.volume` accepts `0f..1f` (linear, device volume unchanged); values
 * outside that band are clamped rather than passed to the player.
 */
object PlayerVolume {
    const val MIN = 0f
    const val MAX = 1f
    const val DEFAULT = 1f
    /** Mute threshold: at or below this the slider is treated as muted. */
    const val MUTE_EPSILON = 0.001f

    /** Clamp any requested volume into the legal [MIN]..[MAX] band. */
    fun clamp(volume: Float): Float = volume.coerceIn(MIN, MAX)

    /** True when the volume is effectively zero (muted). */
    fun isMuted(volume: Float): Boolean = clamp(volume) <= MUTE_EPSILON

    /** 0..100 integer percent for UI labels / accessibility. */
    fun toPercent(volume: Float): Int {
        val clamped = clamp(volume)
        return (clamped * 100f + 0.5f).toInt().coerceIn(0, 100)
    }

    /** Inverse of [toPercent] for slider position restoration. */
    fun fromPercent(percent: Int): Float =
        (percent.coerceIn(0, 100) / 100f).coerceIn(MIN, MAX)

    /** Toggle between zero and the last non-zero level; returns the new level. */
    fun toggleMute(current: Float, lastNonZero: Float = DEFAULT): Float =
        if (isMuted(current)) clamp(lastNonZero.coerceAtLeast(0.1f)) else MIN
}
