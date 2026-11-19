package app.vidmatex.feature.subtitles.transcription

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.model.SubtitleCue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pure, network-free parser for Whisper server responses that carry timed segments
 * (`response_format=verbose_json`). The production media client currently requests
 * `srt` and parses it in `media:processor`; this parser covers the JSON segment shape
 * so the integration contract (real timed segments -> monotonic cues) is unit-tested
 * with inline fixtures, and can be adopted by the client when JSON output is enabled.
 *
 * Rules:
 *  - `segments[].start` / `end` (seconds, floats) become millisecond timestamps;
 *  - segment text lines are kept VERBATIM (Arabic bidi characters and punctuation are
 *    never reordered or normalized; Latin-script spans are copied character-for-char);
 *  - segments are sorted by start time and re-indexed 1..n so cue order is monotonic;
 *  - blank/empty segments are dropped; malformed JSON or an empty segment list yields
 *    an [AppResult.Error] (never a fake/empty success list).
 *
 * No Android imports; no network; fixtures are plain strings.
 */
object WhisperResponseParser {
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

    /** Parses a `verbose_json` payload; returns cues or a plain error message. */
    fun parseVerboseJson(raw: String): AppResult<List<SubtitleCue>> {
        if (raw.isBlank()) {
            return AppResult.Error("Transcription server returned an empty response.")
        }
        val parsed = runCatching { json.decodeFromString<VerboseJson>(raw) }
            .getOrElse { return AppResult.Error("Malformed Whisper JSON response: ${it.message ?: "parse error"}") }
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
        return AppResult.Success(CueOrdering.ensureMonotonic(cues))
    }

    /** Best-effort spoken-language tag from the JSON payload (ISO-639-1 or null). */
    fun languageOf(raw: String): String? = runCatching {
        json.decodeFromString<VerboseJson>(raw).language?.lowercase()?.take(2)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * Defensive cue ordering shared by the worker: sort by start time, clamp inverted
 * ranges, re-index sequentially. Timestamps and line text are otherwise untouched.
 */
object CueOrdering {
    fun ensureMonotonic(cues: List<SubtitleCue>): List<SubtitleCue> =
        cues.sortedWith(compareBy({ it.startMs }, { it.endMs }))
            .mapIndexed { i, cue ->
                cue.copy(
                    index = i + 1,
                    endMs = cue.endMs.coerceAtLeast(cue.startMs),
                )
            }
}
