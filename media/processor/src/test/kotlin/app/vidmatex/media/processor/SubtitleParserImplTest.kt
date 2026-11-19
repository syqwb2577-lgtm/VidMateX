package app.vidmatex.media.processor

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.model.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubtitleParserImplTest {

    private lateinit var parser: SubtitleParserImpl

    @Before
    fun setUp() {
        parser = SubtitleParserImpl()
    }

    @Test
    fun `parse SRT extracts index, timestamps and multi-line text`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            Hello world
            Second line

            2
            00:00:05,500 --> 00:00:09,250
            Another cue
        """.trimIndent()

        val result = parser.parse(SubtitleFormat.SRT, srt)
        assertTrue("expected success, got $result", result is AppResult.Success)
        val cues = (result as AppResult.Success).value

        assertEquals(2, cues.size)
        assertEquals(1, cues[0].index)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(4000L, cues[0].endMs)
        assertEquals(listOf("Hello world", "Second line"), cues[0].lines)

        assertEquals(2, cues[1].index)
        assertEquals(5500L, cues[1].startMs)
        assertEquals(9250L, cues[1].endMs)
        assertEquals(listOf("Another cue"), cues[1].lines)
    }

    @Test
    fun `SRT round trip preserves cues and timestamps`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            Hello world

            2
            00:00:05,500 --> 00:00:09,250
            Another cue
            with two lines
        """.trimIndent()

        val first = (parser.parse(SubtitleFormat.SRT, srt) as AppResult.Success).value
        val written = parser.write(SubtitleFormat.SRT, first)
        val second = (parser.parse(SubtitleFormat.SRT, written) as AppResult.Success).value

        assertEquals(first.size, second.size)
        first.zip(second).forEach { (a, b) ->
            assertEquals(a.index, b.index)
            assertEquals(a.startMs, b.startMs)
            assertEquals(a.endMs, b.endMs)
            assertEquals(a.lines, b.lines)
        }
    }

    @Test
    fun `parse VTT skips header and NOTE blocks and accepts dot timestamps`() {
        val vtt = """
            WEBVTT
            Kind: captions

            NOTE This block is a comment and must be ignored

            00:00:01.000 --> 00:00:04.000
            Hello world

            00:05.500 --> 00:09.250
            Short timestamp without hours
        """.trimIndent()

        val result = parser.parse(SubtitleFormat.VTT, vtt)
        assertTrue("expected success, got $result", result is AppResult.Success)
        val cues = (result as AppResult.Success).value

        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(4000L, cues[0].endMs)
        assertEquals(listOf("Hello world"), cues[0].lines)

        // "00:05.500" = 0 min, 5.5 s = 5500 ms
        assertEquals(5500L, cues[1].startMs)
        assertEquals(9250L, cues[1].endMs)
        assertEquals(listOf("Short timestamp without hours"), cues[1].lines)
    }

    @Test
    fun `parse ASS reads Events section, splits Text on backslash-N and preserves commas in text`() {
        val ass = """
            [Script Info]
            Title: test

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,24

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,Hello\NWorld
            Dialogue: 0,0:00:05.50,0:00:09.25,Default,,0,0,0,,Text, with a comma, preserved
        """.trimIndent()

        val result = parser.parse(SubtitleFormat.ASS, ass)
        assertTrue("expected success, got $result", result is AppResult.Success)
        val cues = (result as AppResult.Success).value

        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(4000L, cues[0].endMs)
        assertEquals(listOf("Hello", "World"), cues[0].lines)

        assertEquals(5500L, cues[1].startMs)
        assertEquals(9250L, cues[1].endMs)
        // Commas inside the Text field must be preserved (split limit = field count).
        assertEquals(listOf("Text, with a comma, preserved"), cues[1].lines)
    }

    @Test
    fun `convert SRT to VTT preserves timestamps`() {
        val srt = """
            1
            00:00:01,500 --> 00:00:04,250
            Convert me
        """.trimIndent()

        val converted = parser.convert(srt, SubtitleFormat.SRT, SubtitleFormat.VTT)
        assertTrue(converted is AppResult.Success)
        val reparsed = parser.parse(SubtitleFormat.VTT, (converted as AppResult.Success).value)
        val cues = (reparsed as AppResult.Success).value

        assertEquals(1, cues.size)
        assertEquals(1500L, cues[0].startMs)
        assertEquals(4250L, cues[0].endMs)
        assertEquals(listOf("Convert me"), cues[0].lines)
    }

    @Test
    fun `malformed input returns Error with a clear message`() {
        val garbage = "this is not a subtitle file at all"
        val result = parser.parse(SubtitleFormat.SRT, garbage)
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.isNotBlank())
    }

    @Test
    fun `write ASS produces valid Events section with Dialogue lines`() {
        val cues = listOf(
            app.vidmatex.core.model.SubtitleCue(1, 1000L, 4000L, listOf("Hello", "World")),
        )
        val out = parser.write(SubtitleFormat.ASS, cues)
        assertTrue(out.contains("[Events]"))
        assertTrue(out.contains("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"))
        assertTrue(out.contains("Dialogue: 0,0:00:01.00,0:00:04.00"))
        assertTrue(out.contains("Hello\\NWorld"))
    }
}
