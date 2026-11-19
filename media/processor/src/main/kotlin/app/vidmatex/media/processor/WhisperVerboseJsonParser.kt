package app.vidmatex.media.processor

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.model.SubtitleCue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Production parser for OpenAI-compatible Whisper `/v1/audio/transcriptions` responses
 * requested with `response_format=verbose_json`. This is the parser the real HTTP client
 * ([WhisperSpeechToText]) consumes — the request and the parser are intentionally the
 * same format (see CONTRACTS: the client MUST NOT request `srt` while a JSON segment
 * parser is wired in).
 *
 * Shape: `{ "language": "en", "duration": 12.3, "segments": [ { "id": 0, "start": 0.0,
 * "end": 2.5, "text": "..." }, ... ] }`. `start`/`end` are seconds (float); `text` may
 * contain newlines. Unknown keys are ignored; `segments` may be absent (treated as empty).
 *
 * Rules (identical to `feature:subtitles` `WhisperResponseParser` so the two never drift):
 *  - segment text lines kept VERBATIM (Arabic bidi / punctuation / Latin spans copied
 *    character-for-character; no reordering, no normalization);
 *  - blank segments dropped; malformed JSON or zero recognizable cues -> [AppResult.Error]
 *    (never a fake empty success);
 *  - cues sorted by start, inverted ranges clamped, re-indexed 1..n (monotonic).
 *
 * Pure JVM: no Android, no OkHttp, no network. Unit-tested with inline fixtures.
 */
object WhisperVerboseJsonParser {
    @Serializable
    private data class VerboseJson(
        val segments: List<Segment> = emptyList(),
        val language: String? = null,
        val duration: Double? = null,
    )

    @Serializable
    private data class Segment(
        val id: Int = 0,
        val start: Double = 0.0,
        val end: Double = 0.0,
        val text: String = "",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** Parses a `verbose_json` payload into monotonic cues, or a plain error message. */
    fun parse(raw: String): AppResult<List<SubtitleCue>> {
        if (raw.isBlank()) {
            return AppResult.Error("Transcription server returned an empty response.")
        }
        val parsed = runCatching { json.decodeFromString<VerboseJson>(raw) }
            .getOrElse {
                return AppResult.Error(
                    "Malformed Whisper JSON response: ${it.message ?: "parse error"}",
                )
            }
        val cues = parsed.segments.mapNotNull { segment ->
            val lines = segment.text
                .replace("\r\n", "\n").replace("\r", "\n")
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (lines.isEmpty()) return@mapNotNull null
            val startMs = Math.round(segment.start * 1000.0)
            val endMs = Math.round(segment.end * 1000.0).coerceAtLeast(startMs)
            SubtitleCue(index = segment.id + 1, startMs = startMs, endMs = endMs, lines = lines)
        }
        if (cues.isEmpty()) {
            return AppResult.Error("Transcription server returned no recognizable cues.")
        }
        return AppResult.Success(WhisperCueOrdering.ensureMonotonic(cues))
    }

    /** Best-effort spoken-language tag (ISO-639-1) from the payload, or null. */
    fun languageOf(raw: String): String? = runCatching {
        json.decodeFromString<VerboseJson>(raw).language?.lowercase()?.take(2)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * Defensive cue ordering shared by the production client and the worker: sort by start
 * time, clamp inverted ranges, re-index sequentially. Timestamps and line text are
 * otherwise untouched. Pure JVM.
 */
object WhisperCueOrdering {
    fun ensureMonotonic(cues: List<SubtitleCue>): List<SubtitleCue> =
        cues.sortedWith(compareBy({ it.startMs }, { it.endMs }))
            .mapIndexed { i, cue ->
                cue.copy(
                    index = i + 1,
                    endMs = cue.endMs.coerceAtLeast(cue.startMs),
                )
            }
}

/**
 * Pure chunk-merge math for long-audio transcription. Each chunk's cues are transcribed
 * on a timeline that starts at 0 for that chunk; [offsetBy] shifts every cue of a chunk
 * by the chunk's nominal start on the source timeline so merged cues are monotonic on the
 * original timeline. A negative or zero offset is a no-op shift (clamped at 0 like the
 * parser shift). Pure JVM, no Android.
 */
object WhisperChunkMerger {
    /**
     * Offset every cue in [cues] by [offsetMs] (both ends). Index and lines are
     * untouched; the caller re-indexes the merged list afterwards. Negative offsets
     * clamp at 0 (end >= start always holds).
     */
    fun offsetBy(cues: List<SubtitleCue>, offsetMs: Long): List<SubtitleCue> {
        if (offsetMs <= 0L) return cues
        return cues.map { cue ->
            val start = cue.startMs + offsetMs
            val end = (cue.endMs + offsetMs).coerceAtLeast(start)
            cue.copy(startMs = start, endMs = end)
        }
    }

    /**
     * Merge per-chunk cue lists (in chunk order) into one monotonic list. Each chunk's
     * cues are offset by [offsets][chunkOffsetsMs] (index-aligned with [chunks]); chunks
     * with fewer offsets than lists get a 0 offset. The result is sorted by start time,
     * clamped, and re-indexed 1..n. Overlapping cues at chunk boundaries (caused by the
     * cross-chunk overlap window) are NOT dropped — they are kept and ordered; callers
     * that want dedup at boundaries can post-filter, but dropping cues risks losing
     * speech, so the default is keep-all.
     */
    fun merge(chunks: List<List<SubtitleCue>>, chunkOffsetsMs: List<Long>): List<SubtitleCue> {
        val merged = ArrayList<SubtitleCue>()
        chunks.forEachIndexed { i, cues ->
            val offset = chunkOffsetsMs.getOrNull(i) ?: 0L
            merged += offsetBy(cues, offset)
        }
        return WhisperCueOrdering.ensureMonotonic(merged)
    }
}
