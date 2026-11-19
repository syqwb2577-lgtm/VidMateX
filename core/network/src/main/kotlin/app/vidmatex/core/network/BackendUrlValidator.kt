package app.vidmatex.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Pure, Android-framework-free validation/normalization for resolver base URLs.
 * Extracted from RetrofitFactory so the rules are unit-testable on the plain JVM
 * (okhttp3.HttpUrl is a JVM class; no Android framework calls here).
 *
 * Security rule: resolver endpoints must be HTTPS. Plain http, malformed strings,
 * and host-less URLs all yield null; callers map null to "not configured / invalid".
 */
object BackendUrlValidator {

    const val SCHEME_HTTPS = "https"

    /** True only for a non-blank, syntactically valid https URL carrying a host. */
    fun isValidHttpsBaseUrl(raw: String): Boolean = toHttpsBaseUrl(raw) != null

    /**
     * Normalizes [raw] to a base [HttpUrl] (always ends with '/').
     * @return null when blank, malformed, non-https, or missing a host.
     */
    fun toHttpsBaseUrl(raw: String): HttpUrl? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // Reject host-less URLs ("https:///" or "https://") before the lenient OkHttp
        // parser can mis-canonicalize a path segment as a host.
        if (Regex("""^https?://(?:/|$)""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) return null
        val withSlash = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        val url = withSlash.toHttpUrlOrNull() ?: return null
        if (url.scheme != SCHEME_HTTPS) return null
        if (url.host.isBlank()) return null
        return url
    }
}
