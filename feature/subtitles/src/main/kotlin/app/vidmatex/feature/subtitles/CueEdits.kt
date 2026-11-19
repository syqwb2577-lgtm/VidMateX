package app.vidmatex.feature.subtitles

import app.vidmatex.core.model.SubtitleCue

/**
 * Pure cue-editing state for the preview pane: per-cue text overrides keyed by cue
 * index. Edits change ONLY [SubtitleCue.lines]; index, startMs and endMs are preserved
 * exactly (same contract as translation). An override that normalizes to blank lines
 * keeps the original cue text — a cue is never emptied by editing.
 *
 * Applied before shift/serialization in [SubtitleExportAssembler] so edits reach the
 * actual export/embedding path, not just the preview. No Android/Compose imports.
 */
object CueEdits {
    /** Splits free-text editor input into trimmed, non-blank lines. Empty when blank. */
    fun normalizeLines(raw: String): List<String> =
        raw.replace("\r\n", "\n").replace("\r", "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    /**
     * Applies [overrides] (cue index -> edited lines). Unknown indexes and empty/blank
     * overrides leave the cue untouched; timestamps and index are copied verbatim.
     */
    fun apply(cues: List<SubtitleCue>, overrides: Map<Int, List<String>>): List<SubtitleCue> =
        cues.map { cue ->
            val edited = overrides[cue.index]?.filter { it.isNotBlank() }
            if (edited.isNullOrEmpty()) cue else cue.copy(lines = edited)
        }
}
