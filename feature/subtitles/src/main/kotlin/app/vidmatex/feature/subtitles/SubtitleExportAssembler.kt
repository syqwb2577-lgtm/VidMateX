package app.vidmatex.feature.subtitles

import app.vidmatex.core.model.SubtitleCue
import app.vidmatex.core.model.SubtitleFormat
import app.vidmatex.media.processor.SubtitleParser

/**
 * Pure export assembly for the subtitle job: cue overrides -> optional translated
 * list -> shift applied to BOTH lists BEFORE format serialization -> serialized text
 * strings carried into `DownloadSpec`.
 *
 * Why pure: the ViewModel currently builds these strings inline; extracting this
 * object lets the shift-math, translated-cue carry-through, and format selection be
 * tested with the real (JVM-instantiable) `SubtitleParserImpl` and controlled cues,
 * without WorkManager or Android framework code.
 *
 * Timestamps: [SubtitleParser.shift] moves both ends; [SubtitleParser.write] renders
 * SRT/VTT/ASS text. Cue text (including Arabic RTL strings and Latin spans) is copied
 * verbatim — no logical reordering at this layer.
 */
data class SubtitleExportBundle(
    val format: SubtitleFormat,
    val originalText: String?,
    val translatedText: String?,
    val useTranslated: Boolean,
    val translatedLanguageCode: String?,
    /** Shift already applied into both text strings; recorded on the spec for audit. */
    val shiftMs: Long,
    val originalCueCount: Int,
    val translatedCueCount: Int,
    val editedCueCount: Int,
) {
    /** The text a downstream writer should finalize first (translated preferred). */
    val primaryText: String? get() = translatedText ?: originalText
}

object SubtitleExportAssembler {
    /**
     * @param cues original/preview cues (adapter sidecar, imported file, or Whisper)
     * @param translatedCues provider output when translation is enabled; null otherwise
     * @param shiftMs user offset applied to every cue before serialization
     * @param format output format chosen in the UI
     * @param parser shared parser (shift + write delegated to `media:processor`)
     * @param targetLanguageCode ISO code when translation produced content
     * @param cueOverrides per-cue text edits from the preview editor
     */
    fun assemble(
        cues: List<SubtitleCue>,
        translatedCues: List<SubtitleCue>?,
        shiftMs: Long,
        format: SubtitleFormat,
        parser: SubtitleParser,
        targetLanguageCode: String?,
        cueOverrides: Map<Int, List<String>> = emptyMap(),
    ): SubtitleExportBundle {
        val editedOriginal = CueEdits.apply(cues, cueOverrides)
        val shiftedOriginal = parser.shift(editedOriginal, shiftMs)
        val originalText = shiftedOriginal
            .takeIf { it.isNotEmpty() }
            ?.let { parser.write(format, it) }

        val translatedText = translatedCues
            ?.takeIf { it.isNotEmpty() }
            ?.let { parser.write(format, parser.shift(it, shiftMs)) }

        val useTranslated = translatedText != null
        return SubtitleExportBundle(
            format = format,
            originalText = originalText,
            translatedText = translatedText,
            useTranslated = useTranslated,
            translatedLanguageCode = if (useTranslated) targetLanguageCode else null,
            shiftMs = shiftMs,
            originalCueCount = shiftedOriginal.size,
            translatedCueCount = translatedCues?.size ?: 0,
            editedCueCount = cueOverrides.filterValues { it.isNotEmpty() }.size,
        )
    }
}
