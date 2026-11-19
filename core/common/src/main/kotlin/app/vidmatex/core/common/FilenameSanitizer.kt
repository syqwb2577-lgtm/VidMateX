package app.vidmatex.core.common

/**
 * Reusable filename / relative-path hardening for every module that writes user- or
 * remote-supplied names into app storage (downloads, sidecar subtitles, transcript
 * files). Pure JVM logic — no Android or IO calls — so the rules are unit-testable.
 *
 * Threats covered (SECURITY.md section 3, "Path traversal in downloaded filenames"):
 *  - path separators `/` `\` and Windows-forbidden chars `: * ? " < > |`;
 *  - ASCII control characters incl. NUL;
 *  - traversal segments `.` / `..` in relative paths;
 *  - Windows drive prefixes (`C:\…`) and absolute paths;
 *  - over-long names truncated on a code-point boundary (Unicode letters incl.
 *    Arabic are preserved; only unsafe characters are replaced).
 */
object FilenameSanitizer {
    const val MAX_FILENAME_LENGTH = 120
    private const val FALLBACK_NAME = "download"

    private val forbiddenChars = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    private val underscoreRun = Regex("_+")
    private val drivePrefix = Regex("^[A-Za-z]:[\\\\/].*")

    private fun isUnsafeChar(c: Char): Boolean =
        c.code in 0x00..0x1F || c.code == 0x7F || c.isWhitespace() || c in forbiddenChars

    /**
     * Sanitizes a single file name (no path separators). Unsafe characters become `_`,
     * repeated separators collapse, edge dots/dashes/underscores are trimmed, blank
     * input falls back to [fallback] (itself sanitized), and the result is capped at
     * [maxLength] code points.
     */
    fun sanitizeFilename(name: String, fallback: String = FALLBACK_NAME, maxLength: Int = MAX_FILENAME_LENGTH): String {
        val cleaned = name.trim()
            .map { c -> if (isUnsafeChar(c)) '_' else c }
            .joinToString("")
            .replace(underscoreRun, "_")
            .trim('_', '.', '-', ' ')
        val safeFallback = sanitizeLiteral(fallback).ifBlank { FALLBACK_NAME }
        val result = cleaned.ifBlank { safeFallback }
        return truncateByCodePoints(result, maxLength).trimEnd('.', ' ', '-', '_')
    }

    /** True when [name] is a safe single-segment file name (no separators/traversal/control chars). */
    fun isSafeFileName(name: String, maxLength: Int = MAX_FILENAME_LENGTH): Boolean {
        if (name.isBlank() || name.length > maxLength) return false
        if (name == "." || name == "..") return false
        if (name.endsWith('.') || name.endsWith(' ')) return false
        if (name.any { it.code in 0x00..0x1F || it.code == 0x7F }) return false
        return forbiddenChars.none { name.contains(it) }
    }

    /**
     * Validates and cleans a relative path made of single-segment file names.
     * Returns the `/`-joined safe path, or null when the input is absolute,
     * drive-qualified, empty, over-segmented, or contains `.`/`..`/unsafe chars.
     */
    fun sanitizeRelativePath(relative: String, maxSegments: Int = 8): String? {
        val trimmed = relative.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith('/') || trimmed.startsWith('\\')) return null
        if (drivePrefix.matches(trimmed)) return null
        val segments = trimmed.split('/', '\\').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.size > maxSegments) return null
        if (segments.any { it == "." || it == ".." }) return null
        if (segments.any { segment -> segment.any { c -> isUnsafeChar(c) } }) return null
        return segments.joinToString("/")
    }

    private fun sanitizeLiteral(s: String): String =
        s.map { c -> if (isUnsafeChar(c)) '_' else c }
            .joinToString("")
            .trim('_', '.', '-', ' ')

    private fun truncateByCodePoints(s: String, max: Int): String {
        if (s.length <= max) return s
        val builder = StringBuilder()
        val iter = s.codePoints().iterator()
        var count = 0
        while (iter.hasNext() && count < max) {
            builder.appendCodePoint(iter.nextInt())
            count++
        }
        return builder.toString()
    }
}
