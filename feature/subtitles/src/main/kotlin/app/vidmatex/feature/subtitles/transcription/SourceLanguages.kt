package app.vidmatex.feature.subtitles.transcription

/**
 * Pure mapping between the UI source-language dropdown and the Whisper `language`
 * form field. Empty string / null = auto-detect: the worker omits the `language`
 * field entirely so the server infers the spoken language. No Android imports.
 */
object SourceLanguages {
    /** Dropdown value meaning "do not send a language code" (server auto-detect). */
    const val AUTO = ""

    /** Supported explicit source codes (ISO-639-1) offered in the dropdown. */
    val SUPPORTED = listOf("en", "ar", "fr", "es", "de", "tr", "zh")

    /** Dropdown value -> Whisper `language` field; null when auto-detect selected. */
    fun toApiCode(dropdownValue: String?): String? {
        val v = dropdownValue?.trim().orEmpty()
        return v.ifBlank { null }
    }

    fun isAuto(dropdownValue: String?): Boolean = dropdownValue.isNullOrBlank()

    /**
     * Normalizes a free-text/explicit code to a lowercase two-letter code;
     * blank or malformed -> null (falls back to auto-detect).
     */
    fun normalize(code: String?): String? {
        val v = code?.trim()?.lowercase().orEmpty()
        if (v.isEmpty()) return null
        return v.takeIf { it.length == 2 && it.all { ch -> ch in 'a'..'z' } }
    }

    /**
     * Dropdown entries in display order: Auto first, then [SUPPORTED].
     * Pair = (dropdownValue, rawCode); the UI maps labels to string resources.
     */
    fun dropdownEntries(): List<Pair<String, String>> =
        listOf(AUTO to "auto") + SUPPORTED.map { it to it }
}
