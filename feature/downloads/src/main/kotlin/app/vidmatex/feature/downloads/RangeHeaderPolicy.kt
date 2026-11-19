package app.vidmatex.feature.downloads

/**
 * Pure HTTP Range / length header parsing (no OkHttp/Android imports; JVM-testable).
 * Centralized so the worker, resume policy, and tests share one interpretation of
 * `Accept-Ranges` and `Content-Range: bytes start-end/total`.
 */
internal object RangeHeaderPolicy {

    /** True when an `Accept-Ranges` header advertises byte ranges. */
    fun acceptsBytes(header: String?): Boolean =
        header?.contains("bytes", ignoreCase = true) == true

    /**
     * Total size from `Content-Range: bytes first-last/total`; supports `*` (unknown) and
     * malformed values by returning null (worker then falls back to `existing + bodyLength`).
     */
    fun parseContentRangeTotal(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val afterSlash = header.trim().substringAfterLast('/', "")
        if (afterSlash.isBlank() || afterSlash == "*") return null
        return afterSlash.toLongOrNull()?.takeIf { it >= 0L }
    }

    /**
     * Resolves the known total for a resumed/appended response: explicit `Content-Range`
     * total wins; otherwise `already-downloaded + Content-Length of this 206 body`.
     */
    fun totalForResumedResponse(
        contentRangeHeader: String?,
        existingBytes: Long,
        bodyContentLength: Long,
    ): Long? {
        parseContentRangeTotal(contentRangeHeader)?.let { return it }
        if (bodyContentLength >= 0L) return existingBytes + bodyContentLength
        return null
    }

    /** Content-Length header value as a non-negative Long; null when absent/illegal. */
    fun parseContentLength(header: String?): Long? {
        val value = header?.trim()?.toLongOrNull() ?: return null
        return value.takeIf { it >= 0L }
    }
}
