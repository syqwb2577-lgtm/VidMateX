package app.vidmatex.player

/**
 * Pure orientation / fullscreen recommendation policy for the preview player (§3).
 * No Android imports: the controller/screen consume [recommend] and own the actual
 * Activity orientation and system-window behavior.
 *
 * DEVICE-VERIFICATION NOTE: true immersive fullscreen (hiding system bars, locking
 * sensor orientation) on a handset must be verified on a device/emulator. This policy
 * only computes the recommendation; the Compose surface maximization toggle and the
 * manifest `configChanges="orientation|screenSize|uiMode|screenLayout|smallestScreenSize"`
 * on `MainActivity` prevent activity recreation on rotation while verification proceeds.
 */
object OrientationPolicy {
    /** Reason codes mapped to localized strings in the screen layer. */
    const val REASON_WIDE_VIDEO = "wide_video"
    const val REASON_PORTRAIC_VIDEO = "portrait_video"
    const val REASON_LONG_FORM = "long_form"
    const val REASON_UNKNOWN = "unknown"

    /** Longer than this (minutes) leans landscape even when dimensions are unknown. */
    const val LONG_FORM_THRESHOLD_MS = 10 * 60 * 1_000L

    data class Recommendation(
        val preferLandscape: Boolean,
        val reason: String,
    )

    /**
     * Recommend an orientation from track dimensions and duration.
     *
     * @param widthPx video track width (0/negative when unknown)
     * @param heightPx video track height (0/negative when unknown)
     * @param durationMs media duration (0/negative when unknown)
     */
    fun recommend(widthPx: Int, heightPx: Int, durationMs: Long): Recommendation {
        val knownShape = widthPx > 0 && heightPx > 0
        if (knownShape) {
            return if (widthPx >= heightPx) {
                Recommendation(true, REASON_WIDE_VIDEO)
            } else {
                Recommendation(false, REASON_PORTRAIC_VIDEO)
            }
        }
        if (durationMs >= LONG_FORM_THRESHOLD_MS) {
            return Recommendation(true, REASON_LONG_FORM)
        }
        return Recommendation(false, REASON_UNKNOWN)
    }
}
