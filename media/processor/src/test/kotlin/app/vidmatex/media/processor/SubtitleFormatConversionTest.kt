package app.vidmatex.media.processor

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.model.SubtitleCue
import app.vidmatex.core.model.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Parser hardening suite (JVM-pure; no Android imports). Covers SRT/VTT/ASS write→parse
 * equality, SRT↔VTT conversion with exact ms timestamps, ASS Dialogue parsing + override-tag
 * stripping, BOM/CRLF tolerance, raw Arabic preservation (no bidi reordering), and malformed
 * input errors. Written but NOT executed here (no SDK/JDK17); status pending CI.
 */
class SubtitleFormatConversionTest {
    private lateinit var parser: SubtitleParserImpl

    @Before
    fun setUp() {
        parser = SubtitleParserImpl()
    }

    private fun success(result: AppResult<List<SubtitleCue>>): List<SubtitleCue> {
        assertTrue("expected Success, got $result", result is AppResult.Success)
        return (result as AppResult.Success).value
    }

    // ------------------------------------------------------------- SRT write→parse
    @Test
    fun `srt write then parse preserves exact milliseconds and multi-line text`() {
        val cues = listOf(
            SubtitleCue(1, 123L, 4_999L, listOf("First line", "Second line")),
            SubtitleCue(2, 60_000L, 61_123L, listOf("Single")),
        )
        val written = parser.write(SubtitleFormat.SRT, cues)
        // SRT comma-decimal timestamps.
        assertTrue(written.contains("00:00:00,123 --> 00:00:04,999"))
        assertTrue(written.contains("00:01:00,000 --> 00:01:01,123"))

        val reparsed = success(parser.parse(SubtitleFormat.SRT, written))
        assertEquals(2, reparsed.size)
        cues.zip(reparsed).forEach { (a, b) ->
            assertEquals(a.index, b.index)
            assertEquals(a.startMs, b.startMs)
            assertEquals(a.endMs, b.endMs)
            assertEquals(a.lines, b.lines)
        }
    }

    @Test
    fun `srt round trip keeps arabic multi-line text verbatim with no reordering`() {
        val cues = listOf(
            SubtitleCue(1, 1_000L, 3_000L, listOf("مرحبا بالعالم", "السطر الثاني هنا")),
            SubtitleCue(2, 4_000L, 6_500L, listOf("خطاب عربي", "سطر آخر")),
        )
        val written = parser.write(SubtitleFormat.SRT, cues)
        val reparsed = success(parser.parse(SubtitleFormat.SRT, written))
        assertEquals(cues.size, reparsed.size)
        cues.zip(reparsed).forEach { (a, b) ->
            assertEquals(a.startMs, b.startMs)
            assertEquals(a.endMs, b.endMs)
            assertEquals(a.lines, b.lines) // exact codepoint sequence; RTL handled by UI layer
        }
    }

    @Test
    fun `srt parse tolerates utf-8 bom and crlf and splits on blank lines`() {
        val raw = "\uFEFF1\r\n00:00:01,500 --> 00:00:03,750\r\nLine one\r\nLine two\r\n\r\n" +
            "2\r\n00:00:04,000 --> 00:00:06,250\r\nSecond cue\r\n"
        val cues = success(parser.parse(SubtitleFormat.SRT, raw))
        assertEquals(2, cues.size)
        assertEquals(1500L, cues[0].startMs)
        assertEquals(3750L, cues[0].endMs)
        assertEquals(listOf("Line one", "Line two"), cues[0].lines)
        assertEquals(4000L, cues[1].startMs)
        assertEquals(6250L, cues[1].endMs)
        assertEquals(listOf("Second cue"), cues[1].lines)
    }

    // ------------------------------------------------------------- VTT write→parse
    @Test
    fun `vtt round trip preserves timestamps and emits no cue numbers or settings`() {
        val cues = listOf(
            SubtitleCue(1, 1_500L, 4_250L, listOf("Hello world")),
            SubtitleCue(2, 5_000L, 9_876L, listOf("Multi", "line cue")),
        )
        val written = parser.write(SubtitleFormat.VTT, cues)
        assertTrue(written.startsWith("WEBVTT"))
        assertTrue(written.contains("00:00:01.500 --> 00:00:04.250"))
        // No cue-identifier number lines (a bare integer line before a timestamp line).
        val bareNumberLines = written.lines().filter { it.trim().matches(Regex("""\d+""")) }
        assertTrue("VTT output must not contain cue numbers: $bareNumberLines", bareNumberLines.isEmpty())
        // Dot-decimal (not comma-decimal) timestamps.
        assertFalse(written.contains(","))

        val reparsed = success(parser.parse(SubtitleFormat.VTT, written))
        assertEquals(2, reparsed.size)
        cues.zip(reparsed).forEach { (a, b) ->
            assertEquals(a.startMs, b.startMs)
            assertEquals(a.endMs, b.endMs)
            assertEquals(a.lines, b.lines)
        }
    }

    @Test
    fun `vtt parse skips header metadata, cue identifiers and note blocks`() {
        val vtt = """
            WEBVTT
            Kind: captions
            Language: ar
            NOTE this comment block must be ignored

            identifier-one
            00:00:01.000 --> 00:00:04.000
            First cue

            00:05.500 --> 00:09.250
            Second cue without identifier
        """.trimIndent()
        val cues = success(parser.parse(SubtitleFormat.VTT, vtt))
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(4000L, cues[0].endMs)
        assertEquals(listOf("First cue"), cues[0].lines)
        assertEquals(5500L, cues[1].startMs)
        assertEquals(9250L, cues[1].endMs)
    }

    // ------------------------------------------------------------- SRT <-> VTT convert
    @Test
    fun `convert srt to vtt preserves start and end milliseconds exactly`() {
        val srt = """
            1
            00:00:01,123 --> 00:00:04,999
            Convert me

            2
            00:01:02,500 --> 00:01:03,001
            Second
        """.trimIndent()
        val converted = parser.convert(srt, SubtitleFormat.SRT, SubtitleFormat.VTT)
        assertTrue(converted is AppResult.Success)
        val out = (converted as AppResult.Success).value
        assertTrue(out.startsWith("WEBVTT"))
        assertTrue(out.contains("00:00:01.123 --> 00:00:04.999"))

        val cues = success(parser.parse(SubtitleFormat.VTT, out))
        assertEquals(2, cues.size)
        assertEquals(1_123L, cues[0].startMs) // 00:00:01,123 = 1s + 123ms
        assertEquals(4_999L, cues[0].endMs)   // 00:00:04,999 = 4s + 999ms
        assertEquals(62_500L, cues[1].startMs)
        assertEquals(63_001L, cues[1].endMs)
    }

    @Test
    fun `convert vtt to srt preserves start and end milliseconds exactly`() {
        val vtt = parser.write(
            SubtitleFormat.VTT,
            listOf(SubtitleCue(1, 250L, 3_750L, listOf("Round", "trip"))),
        )
        val converted = parser.convert(vtt, SubtitleFormat.VTT, SubtitleFormat.SRT)
        assertTrue(converted is AppResult.Success)
        val out = (converted as AppResult.Success).value
        assertTrue(out.contains("00:00:00,250 --> 00:00:03,750"))
        val cues = success(parser.parse(SubtitleFormat.SRT, out))
        assertEquals(1, cues.size)
        assertEquals(250L, cues[0].startMs)
        assertEquals(3_750L, cues[0].endMs)
        assertEquals(listOf("Round", "trip"), cues[0].lines)
    }

    // ------------------------------------------------------------- ASS
    @Test
    fun `ass parse reads dialogue fields and strips override tags and commas in text`() {
        val ass = """
            [Script Info]
            Title: test
            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,24
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.50,0:00:04.25,Default,,0,0,0,,{\an8}Hello {\i1}World\NSecond line
            Dialogue: 0,0:00:05.00,0:00:09.75,Default,,0,0,0,,Text, with a comma, preserved
        """.trimIndent()
        val cues = success(parser.parse(SubtitleFormat.ASS, ass))
        assertEquals(2, cues.size)
        assertEquals(1_500L, cues[0].startMs)
        assertEquals(4_250L, cues[0].endMs)
        assertEquals(listOf("Hello World", "Second line"), cues[0].lines)
        assertEquals(5_000L, cues[1].startMs)
        assertEquals(9_750L, cues[1].endMs)
        assertEquals(listOf("Text, with a comma, preserved"), cues[1].lines)
    }

    @Test
    fun `ass write emits header, format line and dialogue lines with centisecond timestamps`() {
        val cues = listOf(SubtitleCue(1, 1_000L, 4_000L, listOf("Hello", "World")))
        val out = parser.write(SubtitleFormat.ASS, cues)
        assertTrue(out.contains("[Script Info]"))
        assertTrue(out.contains("ScriptType: v4.00+"))
        assertTrue(out.contains("[V4+ Styles]"))
        assertTrue(out.contains("[Events]"))
        assertTrue(out.contains("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"))
        assertTrue(out.contains("Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,Hello\\NWorld"))

        val reparsed = success(parser.parse(SubtitleFormat.ASS, out))
        assertEquals(1, reparsed.size)
        assertEquals(1_000L, reparsed[0].startMs)
        assertEquals(4_000L, reparsed[0].endMs)
        assertEquals(listOf("Hello", "World"), reparsed[0].lines)
    }

    @Test
    fun `convert ass to srt strips override tags and keeps plain text and timestamps`() {
        val ass = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:04.50,Default,,0,0,0,,{\b1}Bold{\r} normal\NSecond {\i9}styled{\i0}
        """.trimIndent()
        val converted = parser.convert(ass, SubtitleFormat.ASS, SubtitleFormat.SRT)
        assertTrue(converted is AppResult.Success)
        val out = (converted as AppResult.Success).value
        assertFalse("override tags must be stripped on ASS→SRT", out.contains("{"))
        assertFalse(out.contains("}"))
        val cues = success(parser.parse(SubtitleFormat.SRT, out))
        assertEquals(1, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(4_500L, cues[0].endMs)
        assertTrue(cues[0].lines.any { it.contains("Bold normal") })
        assertTrue(cues[0].lines.any { it.contains("Second") })
    }

    // ------------------------------------------------------------- errors + language
    @Test
    fun `garbage input returns error for every format`() {
        val garbage = "this is not a subtitle file at all"
        assertTrue(parser.parse(SubtitleFormat.SRT, garbage) is AppResult.Error)
        assertTrue(parser.parse(SubtitleFormat.VTT, garbage) is AppResult.Error)
        assertTrue(parser.parse(SubtitleFormat.ASS, garbage) is AppResult.Error)
    }

    @Test
    fun `detectLanguage recognizes arabic supplement and extended-a blocks`() {
        val supplement = listOf(SubtitleCue(1, 0L, 1000L, listOf("بعض النص ݐ")))
        assertEquals("ar", parser.detectLanguage(supplement))
        val extendedA = listOf(SubtitleCue(1, 0L, 1000L, listOf("ࢠ تجريبي")))
        assertEquals("ar", parser.detectLanguage(extendedA))
    }

    @Test
    fun `latin heuristic returns en for english and null for arabic or empty`() {
        val english = listOf(SubtitleCue(1, 0L, 1000L, listOf("This is ordinary English text")))
        assertEquals("en", parser.latinHeuristicCode(english))
        val arabic = listOf(SubtitleCue(1, 0L, 1000L, listOf("نص عربي")))
        assertEquals(null, parser.latinHeuristicCode(arabic))
        assertEquals(null, parser.latinHeuristicCode(emptyList()))
    }
}
