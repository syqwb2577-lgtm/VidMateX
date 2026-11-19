package app.vidmatex.feature.downloads

/**
 * Pure filename sanitization and extension policy (no Android imports; JVM-testable).
 *
 * Defense-in-depth for stored download names (master requirement §7):
 *  - strip control characters (NUL, CR/LF, tab, etc.);
 *  - collapse path separators (`/`, `\`) and shell/FS-unsafe characters to `_`;
 *  - neutralize traversal (`..`) segments so a malicious title cannot escape a directory;
 *  - reject executable / script / web / archive extensions for media outputs;
 *  - cap length and never emit a blank or dot-only name.
 */
internal object FileNamePolicy {

    const val MAX_NAME_LENGTH = 120

    private val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]+")
    private val CONTROL_CHARS = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]")
    // Collapse runs of separator chars (underscore/hyphen) — dots are NOT included so a
    // file-extension dot is never consumed.
    private val MULTI_SEPARATOR = Regex("[_-]{2,}")
    // Collapse underscore/hyphen separators immediately before the extension dot, but
    // never consume the dot itself (file extensions must be preserved).
    private val SEPARATOR_BEFORE_EXTENSION = Regex("[_-]+(?=\\.[A-Za-z0-9]{1,5}$)")

    /** Extensions that must never be produced for a media download. */
    private val BLOCKED_EXTENSIONS = setOf(
        "exe", "bat", "cmd", "com", "msi", "apk", "app", "deb", "pkg",
        "sh", "bash", "zsh", "ps1", "psm1", "vbs", "jar", "dll", "so",
        "js", "mjs", "html", "htm", "php", "phtml", "jsp", "svg", "bat",
    )

    /** Media / subtitle containers this app ever writes. */
    private val SAFE_MEDIA_EXTENSIONS = setOf(
        "mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "ts",
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "weba",
        "srt", "vtt", "ass",
    )

    /**
     * Sanitizes a title into a safe file base name (no extension). The result never
     * contains a path separator, a control character, a `..` segment, or a leading dot.
     */
    fun sanitize(name: String, fallback: String = "download"): String {
        val withoutControls = CONTROL_CHARS.replace(name.trim(), "")
        // Neutralize any traversal segment before the generic character pass.
        val withoutTraversal = withoutControls
            .replace(Regex("(^|[./\\\\])\\.\\.([./\\\\]|$)"), "$1_$2")
            .replace("..", "_")
        val collapsed = UNSAFE_CHARS.replace(withoutTraversal, "_")
            .replace(MULTI_SEPARATOR, "_")
            .replace(SEPARATOR_BEFORE_EXTENSION, "")
            .trim('_', '.', '-', ' ')
        return collapsed.ifBlank { fallback }
            .take(MAX_NAME_LENGTH)
            .trimEnd('.', ' ', '_', '-')
            .ifBlank { fallback }
    }

    /** True when [ext] (with or without a leading dot) is a media/subtitle container. */
    fun extensionAllowed(ext: String?): Boolean {
        val e = normalizeExt(ext)
        return e.isNotEmpty() && e in SAFE_MEDIA_EXTENSIONS && e !in BLOCKED_EXTENSIONS
    }

    /** True for executable/script/web extensions that must be blocked in media output names. */
    fun dangerousExtension(ext: String?): Boolean = normalizeExt(ext) in BLOCKED_EXTENSIONS

    /**
     * Builds a final display name `base.ext`. An unsafe/unknown extension falls back to
     * [fallbackExt] rather than being written verbatim.
     */
    fun safeDisplayName(baseName: String, extension: String, fallbackExt: String = "mp4"): String {
        val base = sanitize(baseName)
        val ext = if (extensionAllowed(extension)) normalizeExt(extension) else fallbackExt
        return "$base.$ext"
    }

    private fun normalizeExt(ext: String?): String =
        ext?.lowercase()?.trim()?.trimStart('.').orEmpty()
}
