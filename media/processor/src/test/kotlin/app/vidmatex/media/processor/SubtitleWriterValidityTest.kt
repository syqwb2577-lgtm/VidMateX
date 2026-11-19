package app.vidmatex.media.processor

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.model.SubtitleCue
import app.vidmatex.core.model.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure JVM tests for SRT/VTT writer validity: cue numbering, timestamp format,
 * Unicode/Arabic preservation, WEBVTT header, and parse→write→parse round-trip.
 * Uses the real `SubtitleParserImpl`; no Android.
 *
 * Status: COMPLETE_NOT_RUN.
 */
class SubtitleWriterValidityTest {
    private lateinit var parser: SubtitleParserImpl

    @Before
    fun setUp() {
        parser = SubtitleParserImpl()
    }

    private fun cue(i: Int, s: Long, e: Long, vararg lines: String) =
        SubtitleCue(i, s, e, lines.toList())

    @Test
    fun `srt writer emits sequential cue numbers and comma timestamps`() {
        val cues = listOf(cue(1, 0, 1500, "First"), cue(2, 2000, 3500, "Second"))
        val srt = parser.write(SubtitleFormat.SRT, cues)
        val lines = srt.lines()
        // Cue 1 block.
        assertEquals("1", lines[0])
        assertEquals("00:00:00,000 --> 00:00:01,500", lines[1])
        assertEquals("First", lines[2])
        // Cue 2 block (after blank line).
        val idx2 = lines.indexOf("2")
        assertTrue(idx2 > 0)
        assertEquals("00:00:02,000 --> 00:00:03,500", lines[idx2 + 1])
    }

    @Test
    fun `vtt writer starts with WEBVTT header and dot timestamps`() {
        val cues = listOf(cue(1, 1500, 2500, "Hi"))
        val vtt = parser.write(SubtitleFormat.VTT, cues)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("00:00:01.500 --> 00:00:02.500"))
        // VTT must NOT use comma fractional separator.
        assertTrue(!vtt.contains("00:00:01,500"))
    }

    @Test
    fun `arabic unicode and punctuation preserved verbatim in srt and vtt`() {
        val arabic = "مرحبا بالعالم، كيف حالك؟"
        val cues = listOf(cue(1, 0, 1000, arabic))
        val srt = parser.write(SubtitleFormat.SRT, cues)
        val vtt = parser.write(SubtitleFormat.VTT, cues)
        assertTrue(srt.contains(arabic))
        assertTrue(vtt.contains(arabic))
    }

    @Test
    fun `srt parse write parse round trip preserves timestamps and text`() {
        val original = listOf(
            cue(1, 1234, 5678, "Hello"),
            cue(2, 10000, 12500, "World", "second line"),
        )
        val srt = parser.write(SubtitleFormat.SRT, original)
        val reparsed = (parser.parse(SubtitleFormat.SRT, srt) as AppResult.Success).value
        assertEquals(2, reparsed.size)
        assertEquals(1234L, reparsed[0].startMs)
        assertEquals(5678L, reparsed[0].endMs)
        assertEquals(listOf("Hello"), reparsed[0].lines)
        assertEquals(10000L, reparsed[1].startMs)
        assertEquals(12500L, reparsed[1].endMs)
        assertEquals(listOf("World", "second line"), reparsed[1].lines)
    }

    @Test
    fun `vtt parse write parse round trip preserves timestamps`() {
        val original = listOf(cue(1, 1500, 3250, "VTT cue"))
        val vtt = parser.write(SubtitleFormat.VTT, original)
        val reparsed = (parser.parse(SubtitleFormat.VTT, vtt) as AppResult.Success).value
        assertEquals(1, reparsed.size)
        assertEquals(1500L, reparsed[0].startMs)
        assertEquals(3250L, reparsed[0].endMs)
    }

    @Test
    fun `hours and large timestamps format correctly`() {
        val cues = listOf(cue(1, 3_661_500L, 3_662_000L, "Over an hour")) // 1h 1m 1.5s
        val srt = parser.write(SubtitleFormat.SRT, cues)
        assertTrue(srt.contains("01:01:01,500 --> 01:01:02,000"))
        val vtt = parser.write(SubtitleFormat.VTT, cues)
        assertTrue(vtt.contains("01:01:01.500 --> 01:01:02.000"))
    }

    @Test
    fun `empty cue list writes empty string not a crash`() {
        assertEquals("", parser.write(SubtitleFormat.SRT, emptyList()).trim())
        // VTT still emits header even with no cues (valid empty WebVTT).
        assertTrue(parser.write(SubtitleFormat.VTT, emptyList()).startsWith("WEBVTT"))
    }
}
