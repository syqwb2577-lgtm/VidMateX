package app.vidmatex.media.processor

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.common.safeRun
import app.vidmatex.core.model.SubtitleCue
import app.vidmatex.core.model.SubtitleFormat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SRT / VTT / ASS parser + writer. Timestamps are stored in milliseconds and preserved
 * exactly through parse -> write (no rounding; VTT dot-decimal and SRT comma-decimal both
 * map to ms). Malformed input returns [AppResult.Error] with a plain message; no exception
 * escapes. Cue text is kept RAW verbatim (including Arabic bidi characters) — no character
 * reordering or normalization; layout direction is handled by the Compose UI layer.
 */
@Singleton
class SubtitleParserImpl @Inject constructor() : SubtitleParser {
    override fun parse(format: SubtitleFormat, text: String): AppResult<List<SubtitleCue>> =
        safeRun {
            when (format) {
                SubtitleFormat.SRT -> parseSrt(text)
                SubtitleFormat.VTT -> parseVtt(text)
                SubtitleFormat.ASS -> parseAss(text)
            }
        }

    override fun write(format: SubtitleFormat, cues: List<SubtitleCue>): String = when (format) {
        SubtitleFormat.SRT -> writeSrt(cues)
        SubtitleFormat.VTT -> writeVtt(cues)
        SubtitleFormat.ASS -> writeAss(cues)
    }

    override fun convert(text: String, from: SubtitleFormat, to: SubtitleFormat): AppResult<String> {
        if (from == to) return AppResult.Success(text)
        return when (val parsed = parse(from, text)) {
            is AppResult.Success -> AppResult.Success(write(to, parsed.value))
            is AppResult.Error -> parsed
        }
    }

    override fun shift(cues: List<SubtitleCue>, offsetMs: Long): List<SubtitleCue> =
        cues.map { cue ->
            // Both ends move by the identical offset; index and lines are untouched. The
            // end is clamped to the (clamped) start so an extreme negative offset can never
            // invert a cue (endMs >= startMs always holds afterwards).
            val start = (cue.startMs + offsetMs).coerceAtLeast(0L)
            val end = (cue.endMs + offsetMs).coerceAtLeast(start)
            cue.copy(startMs = start, endMs = end)
        }

    override fun detectLanguage(cues: List<SubtitleCue>): String? {
        val sample = cues.take(20).flatMap { it.lines }.joinToString(" ")
        if (sample.isEmpty()) return null
        // Heuristic: any Arabic Unicode block character => "ar". Kept intentionally simple;
        // an explicit language code from the source/subtitle metadata is always preferred.
        // Blocks: Arabic (0600-06FF), Arabic Supplement (0750-077F),
        // Arabic Extended-A (08A0-08FF), plus Presentation Forms-A/B when present.
        if (sample.any {
                it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\u08A0'..'\u08FF' ||
                    it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFF'
            }
        ) {
            return "ar"
        }
        // The optional Latin heuristic lives in [latinHeuristicCode]; the public contract
        // defaults to null for non-Arabic text unless a code is supplied explicitly.
        return null
    }

    /**
     * Optional Latin-script heuristic, kept separate from [detectLanguage]. Returns "en"
     * when the sample has at least three Latin letters and no Hebrew/CJK characters;
     * otherwise null. JVM-pure; used only where a caller wants a best-effort guess.
     */
    internal fun latinHeuristicCode(cues: List<SubtitleCue>): String? {
        val sample = cues.take(20).flatMap { it.lines }.joinToString(" ")
        if (sample.isEmpty()) return null
        val latinLetters = sample.count { (it in 'a'..'z') || (it in 'A'..'Z') }
        val hasOtherScript = sample.any { (it in '\u0590'..'\u05FF') || (it in '\u4E00'..'\u9FFF') }
        if (latinLetters >= 3 && !hasOtherScript) return "en"
        return null
    }

    // ---------------------------------------------------------------- SRT
    // HH:MM:SS,mmm --> HH:MM:SS,mmm  (VTT-style '.' also tolerated on parse)
    private val srtTimestampRe = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )

    private fun parseSrt(text: String): List<SubtitleCue> {
        val normalized = text.normalize().trimStart('\uFEFF')
        val blocks = normalized.split(Regex("""\n[ \t]*\n"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val cues = mutableListOf<SubtitleCue>()
        var nextIndex = 1
        for (block in blocks) {
            val lines = block.split("\n").map { it.trimEnd('\r', ' ') }
            val tsLineIdx = lines.indexOfFirst { it.contains("-->") }
            if (tsLineIdx < 0) continue // skip non-cue blocks (BOM residue, stray header)
            val m = srtTimestampRe.find(lines[tsLineIdx].trim()) ?: continue
            val (start, end) = matchToRange(m)
            val index = lines.subList(0, tsLineIdx)
                .firstOrNull { it.isNotBlank() }
                ?.trim()?.toIntOrNull() ?: nextIndex
            val textLines = lines.subList(tsLineIdx + 1, lines.size)
                .filter { it.isNotBlank() }
            if (textLines.isEmpty()) continue
            cues.add(SubtitleCue(index, start, end, textLines))
            nextIndex = index + 1
        }
        if (cues.isEmpty()) error("No valid SRT cues found (expected index, timestamp, and text lines).")
        // Untrusted-content defense (§10): cap count/lines, strip control chars, order by time.
        return SubtitleSanitizer.orderByTime(SubtitleSanitizer.sanitizeCues(cues))
    }

    private fun writeSrt(cues: List<SubtitleCue>): String = buildString {
        SubtitleSanitizer.sanitizeCues(cues).forEachIndexed { i, cue ->
            val index = if (cue.index >= 0) cue.index else i + 1
            append(index).append('\n')
            append(formatSrt(cue.startMs)).append(" --> ").append(formatSrt(cue.endMs)).append('\n')
            cue.lines.forEach { append(it).append('\n') }
            append('\n')
        }
    }

    // ---------------------------------------------------------------- VTT
    // (HH:)MM:SS.mmm --> (HH:)MM:SS.mmm — hours optional on parse; cue settings after the
    // timestamp are ignored (regex `find`, not full-match); identifiers before the
    // timestamp are skipped.
    private val vttTimestampRe = Regex(
        """(?:(\d{1,2}):)?(\d{1,2}):(\d{2})\.(\d{1,3})\s*-->\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})\.(\d{1,3})"""
    )

    private fun parseVtt(text: String): List<SubtitleCue> {
        val normalized = text.normalize().trimStart('\uFEFF')
        val lines = normalized.split("\n")
        // Drop WEBVTT header block (first line + optional metadata until first blank line).
        var start = 0
        if (lines.firstOrNull()?.trim()?.startsWith("WEBVTT") == true) {
            while (start < lines.size && lines[start].isNotBlank()) start++
        }
        val body = lines.drop(start).joinToString("\n")
        val blocks = body.split(Regex("""\n[ \t]*\n"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val cues = mutableListOf<SubtitleCue>()
        var nextIndex = 1
        for (block in blocks) {
            val blockLines = block.split("\n").map { it.trimEnd('\r', ' ') }
            val first = blockLines.firstOrNull()?.trim().orEmpty()
            // NOTE / STYLE / REGION blocks are ignored entirely.
            if (first.startsWith("NOTE") || first.startsWith("STYLE") || first.startsWith("REGION")) continue
            val tsLineIdx = blockLines.indexOfFirst { it.contains("-->") }
            if (tsLineIdx < 0) continue
            val m = vttTimestampRe.find(blockLines[tsLineIdx].trim()) ?: continue
            val (startMs, endMs) = matchVttRange(m)
            // Any line before the timestamp is a cue identifier; ignored for data.
            val textLines = blockLines.subList(tsLineIdx + 1, blockLines.size)
                .filter { it.isNotBlank() }
                .map { stripVttTags(it) }
            if (textLines.isEmpty()) continue
            cues.add(SubtitleCue(nextIndex++, startMs, endMs, textLines))
        }
        if (cues.isEmpty()) error("No valid WebVTT cues found after the WEBVTT header.")
        return SubtitleSanitizer.orderByTime(SubtitleSanitizer.sanitizeCues(cues))
    }

    private fun stripVttTags(line: String): String =
        line.replace(Regex("""<[^>]+>"""), "").replace("&lrm;", "").replace("&rlm;", "")

    /**
     * WebVTT writer: emits the `WEBVTT` header, dot-decimal timestamps (`.mmm`), and NO
     * cue identifiers/numbers and NO cue settings — plain cues are the portable subset
     * shared with SRT. Cue text lines are copied verbatim.
     */
    private fun writeVtt(cues: List<SubtitleCue>): String = buildString {
        append("WEBVTT\n\n")
        SubtitleSanitizer.sanitizeCues(cues).forEach { cue ->
            append(formatVtt(cue.startMs)).append(" --> ").append(formatVtt(cue.endMs)).append('\n')
            cue.lines.forEach { append(it).append('\n') }
            append('\n')
        }
    }

    // ---------------------------------------------------------------- ASS
    private val assTimestampRe = Regex("""(\d+):(\d{2}):(\d{2})\.(\d{1,2})""")

    /** Fallback field order when a Dialogue block omits a `Format:` line (SSA v4 includes Markers). */
    private val defaultAssFormat = listOf(
        "Markers", "Layer", "Start", "End", "Style", "Name",
        "MarginL", "MarginR", "MarginV", "Effect", "Text",
    )

    private fun parseAss(text: String): List<SubtitleCue> {
        val lines = text.normalize().split("\n")
        var inEvents = false
        var formatFields: List<String>? = null
        val cues = mutableListOf<SubtitleCue>()
        var nextIndex = 1
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                val section = line.removePrefix("[").substringBefore("]").trim().lowercase()
                inEvents = section == "events"
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                formatFields = line.substringAfter(":").split(",").map { it.trim() }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val fmt = formatFields ?: defaultAssFormat
            val content = line.substringAfter(":").trim()
            // Split into exactly fmt.size fields so the Text field may legitimately contain commas.
            val parts = content.split(",", limit = fmt.size)
            if (parts.size < fmt.size) continue
            val startIdx = fmt.indexOfFirst { it.equals("Start", ignoreCase = true) }
            val endIdx = fmt.indexOfFirst { it.equals("End", ignoreCase = true) }
            val textIdx = fmt.indexOfFirst { it.equals("Text", ignoreCase = true) }
            if (startIdx < 0 || endIdx < 0 || textIdx < 0) continue
            val start = parseAssTimestamp(parts[startIdx].trim())
            val end = parseAssTimestamp(parts[endIdx].trim())
            val rawText = parts[textIdx]
            val cleaned = rawText.replace(Regex("""\{[^}]*\}"""), "") // strip override tags {\...}
            val textLines = cleaned.split(Regex("""\\N|\\n|\\h"""))
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (textLines.isEmpty()) continue
            cues.add(SubtitleCue(nextIndex++, start, end, textLines))
        }
        if (cues.isEmpty()) error("No valid ASS Dialogue lines found in an [Events] section.")
        return SubtitleSanitizer.orderByTime(SubtitleSanitizer.sanitizeCues(cues))
    }

    private fun parseAssTimestamp(s: String): Long {
        val m = assTimestampRe.find(s) ?: error("Malformed ASS timestamp: $s")
        val (h, mm, ss, cc) = m.destructured
        // cc = centiseconds (1/100 s); 1-2 digits both accepted.
        return h.toLong() * 3_600_000 + mm.toLong() * 60_000 + ss.toLong() * 1000 + cc.toLong() * 10
    }

    /**
     * Best-effort ASS writer: emits a minimal valid header ([Script Info], default style,
     * [Events] Format) and one `Dialogue:` line per cue. Style/Name/Margin/Effect fields are
     * not round-tripped because [SubtitleCue] has no corresponding fields (see CHANGES_REQUESTED.md);
     * timestamps and text (override tags already stripped at parse) are preserved.
     */
    private fun writeAss(cues: List<SubtitleCue>): String = buildString {
        appendLine("[Script Info]")
        appendLine("ScriptType: v4.00+")
        appendLine("PlayResX: 384")
        appendLine("PlayResY: 288")
        appendLine()
        appendLine("[V4+ Styles]")
        appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
        appendLine("Style: Default,Arial,24,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1")
        appendLine()
        appendLine("[Events]")
        appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        for (cue in SubtitleSanitizer.sanitizeCues(cues)) {
            val text = cue.lines.joinToString("\\N")
            append("Dialogue: 0,")
            append(formatAss(cue.startMs)).append(',')
            append(formatAss(cue.endMs)).append(',')
            append("Default,,0,0,0,,")
            appendLine(text)
        }
    }

    // ---------------------------------------------------------------- helpers
    private fun String.normalize(): String = replace("\r\n", "\n").replace("\r", "\n")

    private fun matchToRange(m: MatchResult): Pair<Long, Long> {
        val (h1, m1, s1, f1, h2, m2, s2, f2) = m.destructured
        return toMs(h1, m1, s1, f1) to toMs(h2, m2, s2, f2)
    }

    private fun matchVttRange(m: MatchResult): Pair<Long, Long> {
        val g = m.groupValues
        // groupValues[0] is full match; groups 1-4 start, 5-8 end (hours may be empty)
        val start = toMs(g[1].ifEmpty { "0" }, g[2], g[3], g[4])
        val end = toMs(g[5].ifEmpty { "0" }, g[6], g[7], g[8])
        return start to end
    }

    private fun toMs(h: String, m: String, s: String, frac: String): Long {
        val ms = when (frac.length) {
            1 -> frac.toInt() * 100
            2 -> frac.toInt() * 10
            else -> frac.take(3).padEnd(3, '0').toInt()
        }
        return h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + ms
    }

    private fun formatSrt(ms: Long): String {
        val h = ms / 3_600_000; val r = ms % 3_600_000
        val m = r / 60_000; val r2 = r % 60_000
        val s = r2 / 1000; val milli = r2 % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
    }

    private fun formatVtt(ms: Long): String {
        val h = ms / 3_600_000; val r = ms % 3_600_000
        val m = r / 60_000; val r2 = r % 60_000
        val s = r2 / 1000; val milli = r2 % 1000
        return "%02d:%02d:%02d.%03d".format(h, m, s, milli)
    }

    /** ASS timestamp: H:MM:SS.cc (hours without leading zero, centiseconds). */
    private fun formatAss(ms: Long): String {
        val h = ms / 3_600_000; val r = ms % 3_600_000
        val m = r / 60_000; val r2 = r % 60_000
        val s = r2 / 1000; val cs = (r2 % 1000) / 10
        return "%d:%02d:%02d.%02d".format(h, m, s, cs)
    }
}
