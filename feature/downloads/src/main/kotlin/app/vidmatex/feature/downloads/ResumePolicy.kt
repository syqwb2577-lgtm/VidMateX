package app.vidmatex.feature.downloads

/**
 * Pure resume-decision logic for HTTP Range downloads (no Android / OkHttp imports).
 *
 * Rules (contract task 2):
 *  - No partial bytes → fresh GET, no `Range` header.
 *  - Track declares `resumable = true` (Accept-Ranges seen at resolve time) → send Range.
 *  - Track `resumable` unknown/false → the worker performs a lightweight HEAD/Range
 *    probe; [probeAllowed] consolidates that result before the request is sent.
 *  - Server answers 206 Partial Content → [Decision.APPEND] onto the existing temp file.
 *  - Server answers 200 OK (ignored the Range header) → [Decision.RESTART_FROM_ZERO]:
 *    never append onto a full-200 body; delete the partial and write from byte zero
 *    (previous behavior preserved: a 200 restarts the download).
 *  - 416 Range Not Satisfiable (stale partial) → restart from zero.
 *  - Other non-success codes → [Decision.UNSUPPORTED_RANGE]; the retry classifier decides
 *    whether to retry or fail permanently.
 */
internal object ResumePolicy {
    enum class Decision { FRESH_GET, APPEND, RESTART_FROM_ZERO, UNSUPPORTED_RANGE }

    /** Parse an `Accept-Ranges` header; only `bytes` range units are consumed. */
    fun acceptRangesAllowed(header: String?): Boolean =
        header?.contains("bytes", ignoreCase = true) == true

    /**
     * Whether a `Range` header may be sent for a partial file:
     *  - track.resumable == true  → allowed (adapter already saw Accept-Ranges)
     *  - track.resumable == false → allowed only if the probe header says bytes
     *  - track.resumable == null  → allowed only if the probe header says bytes
     */
    fun probeAllowed(trackResumable: Boolean?, acceptRangesHeader: String?): Boolean = when (trackResumable) {
        true -> true
        false, null -> acceptRangesAllowed(acceptRangesHeader)
    }

    /** Interpret the actual response for a request carrying (or omitting) Range. */
    fun decide(partialBytes: Long, responseCode: Int, acceptRangesHeader: String?): Decision {
        if (partialBytes <= 0L) return Decision.FRESH_GET
        return when (responseCode) {
            206 -> Decision.APPEND
            200 -> Decision.RESTART_FROM_ZERO
            416 -> Decision.RESTART_FROM_ZERO
            in 204..299 ->
                if (acceptRangesAllowed(acceptRangesHeader)) Decision.APPEND else Decision.RESTART_FROM_ZERO
            else -> Decision.UNSUPPORTED_RANGE
        }
    }

    fun appends(decision: Decision): Boolean = decision == Decision.APPEND
}
