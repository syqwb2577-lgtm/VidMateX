package app.vidmatex.core.common

/**
 * Pure-JVM, Android-free secret redaction for log lines and user-facing error
 * messages. Network/UI code must route any text that might carry a token through
 * [redact] before it is logged or shown; the app never prints API keys, bearer
 * tokens, signatures, or password query parameters.
 *
 * Policy:
 *  - `api_key=…`, `X-API-Key: …`, `Authorization: …`, `token=…`, `signature=…`,
 *    `password=…` style key/value pairs keep the key name and replace the value
 *    with `[redacted]`.
 *  - `Bearer <token>` schemes collapse to `Bearer [redacted]`.
 *  - Non-secret text passes through unchanged; blank input returns as-is.
 */
object SecretTextRedactor {
    private val keyValuePattern = Regex(
        """(?i)\b((?:api[-_]?key|key|x-api-key|authorization|access[-_]?token|token|secret|signature|password))\b\s*[:=]\s*["']?[A-Za-z0-9._\-+/=]{8,}["']?"""
    )
    private val bearerPattern = Regex("""(?i)\bbearer\s+[A-Za-z0-9._\-+/=]{12,}""")
    /** Literal API-key tokens of the `sk_live_…` / `pk_test_…` form (OpenAI/Stripe-style). */
    private val apiKeyLiteralPattern = Regex("""\b(?:sk|pk|rk)_(?:live|test|prod)_[A-Za-z0-9_\-]{8,}""")
    /** OpenAI-style bare `sk-…` literal tokens (>=16 chars, matching the CI secret-scan
     *  threshold to avoid false positives on short identifiers like `sk-1`). */
    private val openAiKeyLiteralPattern = Regex("""\bsk-[A-Za-z0-9_\-]{16,}""")

    fun redact(text: String): String {
        if (text.isBlank()) return text
        var out = keyValuePattern.replace(text) { match ->
            "${match.groupValues[1]}=[redacted]"
        }
        out = bearerPattern.replace(out, "Bearer [redacted]")
        out = apiKeyLiteralPattern.replace(out, "[redacted]")
        out = openAiKeyLiteralPattern.replace(out, "[redacted]")
        return out
    }
}
