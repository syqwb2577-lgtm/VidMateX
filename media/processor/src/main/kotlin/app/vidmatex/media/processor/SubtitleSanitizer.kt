package app.vidmatex.media.processor

import app.vidmatex.core.model.SubtitleCue

/**
 * Defensive sanitization for untrusted subtitle/media metadata (CONTRACTS §10). Pure
 * JVM object — no Android imports — covered by `SubtitleHardeningTest`.
 *
 * Threats handled:
 *  - C0 control bytes / DEL embedded in cue text (terminal noise, crashes in some
 *    renderers);
 *  - Newline/CR injection that would forge extra SRT blocks or VTT cues on export;
 *  - Oversized cue counts and lines (zip-bomb / malicious file exhaust);
 *  - Path separators and reserved characters in display/output file names;
 *  - Arabic/RTL content: codepoints are preserved verbatim — no bidi reordering, no
 *    punctuation stripping (Arabic comma،, semicolon؛, question mark؟ kept as-is).
 */
object SubtitleSanitizer {
    /** Hard cap on cues accepted from an untrusted file. */
    const val MAX_CUES = 5_000
    /** Hard cap on characters per cue line. */
    const val MAX_LINE_CHARS = 2_000
    /** Display title cap. */
    const val MAX_TITLE_CHARS = 120
    /** File-name cap. */
    const val MAX_FILE_NAME_CHARS = 80

    // C0 controls (0x00–0x08, 0x0B, 0x0C, 0x0E–0x1F) and DEL (0x7F). Tabs are
    // normalized to spaces rather than dropped; newlines are handled before this regex.
    private val CONTROL_RE = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val WHITESPACE_RE = Regex("\\s+")
    private val FILE_FORBIDDEN_RE = Regex("[/\\\\:*?\"<>|\\u0000-\\u001F]")

    /**
     * Sanitize one cue line: take the first physical line (kill block injection), strip
     * control bytes, normalize whitespace, cap length. Arabic letters and punctuation are
     * untouched.
     */
    fun sanitizeLine(raw: String): String {
        val firstPhysical = raw.replace("\r", "\n").substringBefore('\n').replace('\t', ' ')
        val cleaned = CONTROL_RE.replace(firstPhysical, "")
            .let { WHITESPACE_RE.replace(it, " ") }
            .trim()
        return cleaned.take(MAX_LINE_CHARS)
    }

    /**
     * Sanitize a cue list defensively: cap cue count, sanitize every line, drop lines that
     * become blank, and keep a placeholder so a cue never collapses to an empty block.
     */
    fun sanitizeCues(cues: List<SubtitleCue>, maxCues: Int = MAX_CUES): List<SubtitleCue> =
        cues.take(maxCues).map { cue ->
            val safeLines = cue.lines.map { sanitizeLine(it) }.filter { it.isNotBlank() }
            cue.copy(lines = safeLines.ifEmpty { listOf("") })
        }

    /**
     * Stable chronological ordering: sort by (startMs, original index). The original
     * [SubtitleCue.index] values are preserved (export identity); only the emission order
     * changes. Equal timestamps keep input order (stable sort).
     */
    fun orderByTime(cues: List<SubtitleCue>): List<SubtitleCue> =
        cues.sortedWith(compareBy({ it.startMs }, { it.index }))

    /**
     * Safe file name for an output/media title: drop path components and reserved
     * characters, collapse whitespace, cap length, never return empty or "."/"..".
     */
    fun sanitizeFileName(raw: String?, fallback: String = "media"): String {
        val leaf = raw.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
        val cleaned = FILE_FORBIDDEN_RE.replace(leaf, "")
            .let { WHITESPACE_RE.replace(it, " ") }
            .trim()
            .trim('.')
        val safe = cleaned.take(MAX_FILE_NAME_CHARS).trim()
        return safe.ifBlank { fallback }
    }

    /** Safe display title (metadata/UI): control bytes removed, newlines flattened, capped. */
    fun sanitizeDisplayTitle(raw: String?, fallback: String = "Untitled"): String {
        val flattened = CONTROL_RE.replace(raw.orEmpty(), "")
            .replace("\r", " ")
            .replace("\n", " ")
        val cleaned = WHITESPACE_RE.replace(flattened, " ").trim()
        return cleaned.take(MAX_TITLE_CHARS).ifBlank { fallback }
    }
}
