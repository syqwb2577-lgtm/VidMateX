package app.vidmatex.media.processor

import app.vidmatex.core.model.SubtitleCue

/**
 * Pure, Android-free helpers shared by every [SubtitleTranslator] implementation.
 *
 * Translation contract (CONTRACTS §2): a translation changes ONLY [SubtitleCue.lines];
 * `index`, `startMs`, `endMs` and the cue count are preserved exactly. A blank or missing
 * translation for a cue falls back to the original cue lines — a cue is never dropped and
 * never collapses to empty text. No network or ML Kit classes are referenced here, so the
 * rules below are unit-tested on the plain JVM with fake translators.
 */
internal object CueTranslationMapper {

    /**
     * Apply per-cue translated line lists onto the original cues. [translations] may be
     * shorter than [cues] (missing entries fall back); a null entry or a list that filters
     * down to nothing keeps the original lines. Timestamps and index are copied verbatim.
     */
    fun applyPerCue(
        cues: List<SubtitleCue>,
        translations: List<List<String>?>,
    ): List<SubtitleCue> = cues.mapIndexed { i, cue ->
        val translated = translations.getOrNull(i)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
        cue.copy(lines = translated?.takeIf { it.isNotEmpty() } ?: cue.lines)
    }

    /**
     * Normalize raw translated text into exactly [targetCount] slots, honoring embedded
     * newlines; when the text is blank or contains no usable words, fall back to
     * [fallbackLines] (the original cue) so a cue never loses its text.
     */
    fun splitToLines(raw: String, targetCount: Int, fallbackLines: List<String>): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return fallbackLines
        if (targetCount <= 1) return listOf(trimmed)

        val byNewline = trimmed.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (byNewline.isEmpty()) return fallbackLines

        if (byNewline.size >= targetCount) {
            // Merge excess lines back so we never exceed the original line count.
            val head = byNewline.take(targetCount - 1)
            val tail = byNewline.drop(targetCount - 1).joinToString(" ")
            return head + tail
        }
        if (byNewline.size in 2 until targetCount) {
            // Distribute words of the last line across the remaining slots.
            val head = byNewline.dropLast(1)
            val words = byNewline.last().split(" ").filter { it.isNotBlank() }
            return head + distribute(words, targetCount - head.size)
        }
        val words = trimmed.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return fallbackLines
        return distribute(words, targetCount)
    }

    /** Evenly distribute [words] across [slots]; underfull lists pad with blank slots. */
    internal fun distribute(words: List<String>, slots: Int): List<String> {
        if (slots <= 1) return listOf(words.joinToString(" ").ifEmpty { " " })
        if (words.size <= slots) return words + List(slots - words.size) { " " }
        val per = words.size / slots
        val out = mutableListOf<String>()
        var idx = 0
        repeat(slots) { s ->
            val take = if (s == slots - 1) words.size - idx else per
            out.add(words.subList(idx, idx + take).joinToString(" ").ifEmpty { " " })
            idx += take
        }
        return out
    }

    /**
     * Result of translating + delaying cues for export/burn. Timestamps carry into the
     * spec model (not just UI state): [cues] keep index and translated lines and receive
     * the applied delay; [languageCode] is the ISO tag written into the subtitle track
     * metadata; [shiftMs] mirrors `DownloadSpec.subtitleShiftMs`.
     */
    data class TranslatedCueExport(
        val cues: List<SubtitleCue>,
        val shiftMs: Long,
        val languageCode: String?,
        val useTranslation: Boolean = true,
    ) {
        /** Values mirrored onto `DownloadSpec` fields when the enqueue spec is built. */
        fun specFields() = TranslationSpecFields(
            useTranslatedSubtitle = useTranslation,
            translatedLanguageCode = languageCode,
            subtitleShiftMs = shiftMs,
            translatedCueCount = cues.size,
        )
    }

    /** Subset of `DownloadSpec` translation fields produced from translated cues. */
    data class TranslationSpecFields(
        val useTranslatedSubtitle: Boolean,
        val translatedLanguageCode: String?,
        val subtitleShiftMs: Long,
        val translatedCueCount: Int,
    )

    /**
     * Apply per-cue translations and a constant delay, preserving count, index and
     * ordering. A non-negative [shiftMs] moves both ends; negative values clamp at zero
     * exactly like [SubtitleParser.shift]. The language tag is trimmed/lowercased to two
     * chars; blank → null. Arabic/RTL text and punctuation survive verbatim.
     */
    fun buildExport(
        cues: List<SubtitleCue>,
        translations: List<List<String>?>,
        shiftMs: Long = 0L,
        languageCode: String? = null,
    ): TranslatedCueExport {
        val mapped = applyPerCue(cues, translations)
        val shifted = mapped.map { cue ->
            val start = (cue.startMs + shiftMs).coerceAtLeast(0L)
            val end = (cue.endMs + shiftMs).coerceAtLeast(start)
            cue.copy(startMs = start, endMs = end)
        }
        val tag = languageCode?.trim()?.lowercase()?.take(2)?.takeIf { it.isNotBlank() }
        return TranslatedCueExport(shifted, shiftMs, tag)
    }
}
