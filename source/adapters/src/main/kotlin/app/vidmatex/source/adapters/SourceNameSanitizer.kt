package app.vidmatex.source.adapters

/**
 * Pure sanitization for untrusted metadata that reaches UI titles and output file names
 * (CONTRACTS §10): control bytes, path separators and reserved filename characters are
 * removed; values are length-capped. No Android imports; unit tested in
 * `DirectUrlAdapterLogicTest` / `ResolverPolicyTest`.
 */
object SourceNameSanitizer {
    private val CONTROL_RE = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val WHITESPACE_RE = Regex("\\s+")
    private val FILE_FORBIDDEN_RE = Regex("[/\\\\:*?\"<>|]")
    private const val MAX_TITLE_CHARS = 120
    private const val MAX_FILE_CHARS = 80

    /** Display title: control bytes removed, newlines flattened, collapsed and capped. */
    fun sanitizeDisplayTitle(raw: String?, fallback: String = "Untitled"): String {
        val cleaned = CONTROL_RE.replace(raw.orEmpty(), "")
            .replace("\r", " ")
            .replace("\n", " ")
        val collapsed = WHITESPACE_RE.replace(cleaned, " ").trim()
        return collapsed.take(MAX_TITLE_CHARS).ifBlank { fallback }
    }

    /** File name: basename only; separators and reserved characters dropped; capped. */
    fun sanitizeFileName(raw: String?, fallback: String = "media"): String {
        val leaf = raw.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
        val cleaned = FILE_FORBIDDEN_RE.replace(leaf, "")
        val collapsed = WHITESPACE_RE.replace(cleaned, " ").trim().trim('.')
        return collapsed.take(MAX_FILE_CHARS).ifBlank { fallback }
    }
}
