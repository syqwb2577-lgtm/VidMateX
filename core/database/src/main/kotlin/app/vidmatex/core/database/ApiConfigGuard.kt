package app.vidmatex.core.database

/**
 * Pure-JVM guards for [ApiConfigRefEntity] rows: canonical aliases, safe non-secret
 * URLs, and rejection of token-like values in free-text fields. SQL execution itself
 * is Room/SQLite (device/instrumented); these rules are covered by plain JUnit tests.
 *
 * Security contract: rows store endpoint references only. Any value that looks like a
 * credential is dropped rather than persisted; the actual key lives in the encrypted
 * secrets store and is referenced by [ApiConfigRefEntity.keyAlias].
 */
object ApiConfigGuard {
    const val MAX_ALIAS_LENGTH = 40
    private val aliasRegex = Regex("^[a-z0-9][a-z0-9_-]{0,39}$")

    /** Stable row key: lowercase alnum/hyphen/underscore, max [MAX_ALIAS_LENGTH] chars. */
    fun isValidAlias(alias: String): Boolean = alias.trim().matches(aliasRegex)

    /**
     * Blank (unconfigured) or an https URL with no userinfo and no control characters.
     * Fetch-time private-IP validation belongs to the network/validator layer; this
     * guard only prevents obviously unsafe strings from being recorded.
     */
    fun isSafeBaseUrl(url: String): Boolean {
        // Reject control chars on the raw input BEFORE trim() (trailing \n would otherwise
        // be stripped and slip through).
        if (url.any { it.code in 0x00..0x1F || it.code == 0x7F }) return false
        val trimmed = url.trim()
        if (trimmed.isBlank()) return true
        val lower = trimmed.lowercase()
        if (!lower.startsWith("https://")) return false
        if (lower.contains('@')) return false
        if (lower.any { it.code in 0x00..0x1F || it.code == 0x7F }) return false
        return true
    }

    /** Heuristic: long mixed-case alnum token runs with digits look like credentials. */
    fun looksLikeSecret(value: String): Boolean {
        val v = value.trim()
        if (v.length < 16) return false
        if (v.any { it.isWhitespace() }) return false
        val allowedChars = v.all { it.isLetterOrDigit() || it in "-_./+==" }
        return allowedChars &&
            v.any { it.isDigit() } &&
            v.any { it.isLowerCase() } &&
            v.any { it.isUpperCase() }
    }

    /** Cleans a free-text field for storage; returns "" when it resembles a secret. */
    fun sanitizeField(value: String, maxLen: Int = 200): String {
        if (looksLikeSecret(value)) return ""
        return value.filterNot { it.code in 0x00..0x1F || it.code == 0x7F }.trim().take(maxLen)
    }
}
