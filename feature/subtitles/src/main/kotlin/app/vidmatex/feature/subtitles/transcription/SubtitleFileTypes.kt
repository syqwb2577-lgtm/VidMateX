package app.vidmatex.feature.subtitles.transcription

import app.vidmatex.core.model.SubtitleFormat
import app.vidmatex.core.model.SubtitleKind

/**
 * Pure extension/format/provenance rules for standalone subtitle files imported from
 * the device (separate-subtitle flow). No Android imports; the importer in
 * [TranscriptionFileImporter] delegates its validation here so the rules are covered
 * by plain JVM tests.
 *
 * Supported containers: `.srt`, `.vtt`, `.ass`. Parsing itself is delegated to the
 * shared `media:processor` [app.vidmatex.media.processor.SubtitleParser]; this object
 * only decides the extension/format mapping and the provenance tag.
 */
object SubtitleFileTypes {
    const val SRT_EXT = "srt"
    const val VTT_EXT = "vtt"
    const val ASS_EXT = "ass"

    val SUPPORTED_EXTENSIONS: Set<String> = setOf(SRT_EXT, VTT_EXT, ASS_EXT)

    /** Lowercased file extension without the dot; null when absent/blank. */
    fun extensionOf(fileName: String?): String? {
        // substringAfterLast with NO explicit missingDelimiterValue returns the whole
        // string when the separator is absent (a bare filename with no path). Passing ""
        // there would erase bare filenames entirely — do not re-add that default.
        val name = fileName?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.ifBlank { null }
    }

    /** Format for a filename's extension; null for unsupported/unknown types. */
    fun formatForExtension(fileName: String?): SubtitleFormat? = when (extensionOf(fileName)) {
        SRT_EXT -> SubtitleFormat.SRT
        VTT_EXT -> SubtitleFormat.VTT
        ASS_EXT -> SubtitleFormat.ASS
        else -> null
    }

    fun isSupportedSubtitleFileName(fileName: String?): Boolean =
        formatForExtension(fileName) != null

    /**
     * Provenance tag for a user-imported sidecar: human-provided file (never relabeled
     * as official or auto-generated). Whisper outputs stay [SubtitleKind.AUTOMATIC];
     * adapter-fetched platform tracks keep their adapter-assigned kinds.
     */
    val IMPORTED_PROVENANCE: SubtitleKind = SubtitleKind.HUMAN
}
