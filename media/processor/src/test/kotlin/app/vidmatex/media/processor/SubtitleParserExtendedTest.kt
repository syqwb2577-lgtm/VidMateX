package app.vidmatex.media.processor

import app.vidmatex.core.model.SubtitleCue
import app.vidmatex.core.model.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extended tests for [SubtitleParserImpl]: SRT/VTT/ASS round-trip, shift, UTF-8 Arabic cues.
 * Complements the existing SubtitleParserImplTest and SubtitleShiftAndLanguageTest.
 *
 * Written but NOT executed (no Android SDK/JDK17 in this environment). Status: COMPLETE_NOT_RUN.
 */
class SubtitleParserExtendedTest {

    private val parser = SubtitleParserImpl()

    private val sampleCues = listOf(
        SubtitleCue(1, 0L, 2_500L, listOf("Hello world")),
        SubtitleCue(2, 3_000L, 5_000L, listOf("Second cue", "with two lines")),
    )

    @Test
    fun `srt round trip preserves timestamps and text`() {
        val written = parser.write(SubtitleFormat.SRT, sampleCues)
        val parsed = parser.parse(SubtitleFormat.SRT, written)
        assertTrue(parsed is app.vidmatex.core.common.AppResult.Success)
        val cues = (parsed as app.vidmatex.core.common.AppResult.Success).value
        assertEquals(2, cues.size)
        assertEquals(0L, cues[0].startMs)
        assertEquals(2_500L, cues[0].endMs)
        assertEquals("Hello world", cues[0].lines[0])
        assertEquals(3_000L, cues[1].startMs)
        assertEquals(2, cues[1].lines.size)
    }

    @Test
    fun `vtt round trip preserves cues`() {
        val written = parser.write(SubtitleFormat.VTT, sampleCues)
        assertTrue(written.startsWith("WEBVTT"))
        val parsed = parser.parse(SubtitleFormat.VTT, written)
        assertTrue(parsed is app.vidmatex.core.common.AppResult.Success)
        val cues = (parsed as app.vidmatex.core.common.AppResult.Success).value
        assertEquals(2, cues.size)
        assertEquals(2_500L, cues[0].endMs)
    }

    @Test
    fun `ass round trip preserves cues`() {
        val written = parser.write(SubtitleFormat.ASS, sampleCues)
        assertTrue(written.contains("[Script Info]"))
        assertTrue(written.contains("[Events]"))
        val parsed = parser.parse(SubtitleFormat.ASS, written)
        assertTrue(parsed is app.vidmatex.core.common.AppResult.Success)
        val cues = (parsed as app.vidmatex.core.common.AppResult.Success).value
        assertEquals(2, cues.size)
    }

    @Test
    fun `shift positive offsets all cues`() {
        val shifted = parser.shift(sampleCues, 1_000L)
        assertEquals(1_000L, shifted[0].startMs)
        assertEquals(3_500L, shifted[0].endMs)
        assertEquals(4_000L, shifted[1].startMs)
    }

    @Test
    fun `shift negative clamps at zero`() {
        val shifted = parser.shift(sampleCues, -5_000L)
        assertEquals(0L, shifted[0].startMs)
        assertEquals(0L, shifted[0].endMs)
    }

    @Test
    fun `arabic utf8 cues round trip through srt`() {
        val arabicCues = listOf(
            SubtitleCue(1, 1_000L, 3_000L, listOf("مرحبا بالعالم", "السطر الثاني")),
        )
        val written = parser.write(SubtitleFormat.SRT, arabicCues)
        // Written text must contain the Arabic glyphs (UTF-8 preserved).
        assertTrue(written.contains("مرحبا بالعالم"))
        assertTrue(written.contains("السطر الثاني"))
        val parsed = parser.parse(SubtitleFormat.SRT, written)
        assertTrue(parsed is app.vidmatex.core.common.AppResult.Success)
        val cues = (parsed as app.vidmatex.core.common.AppResult.Success).value
        assertEquals("مرحبا بالعالم", cues[0].lines[0])
        assertEquals("السطر الثاني", cues[0].lines[1])
    }

    @Test
    fun `detectLanguage returns ar for arabic text`() {
        val arabicCues = listOf(
            SubtitleCue(1, 0L, 1_000L, listOf("هذا نص عربي")),
        )
        assertEquals("ar", parser.detectLanguage(arabicCues))
    }

    @Test
    fun `detectLanguage returns null for english`() {
        val englishCues = listOf(
            SubtitleCue(1, 0L, 1_000L, listOf("This is English text")),
        )
        assertEquals(null, parser.detectLanguage(englishCues))
    }

    @Test
    fun `convert srt to vtt adds webvtt header`() {
        val srt = parser.write(SubtitleFormat.SRT, sampleCues)
        val converted = parser.convert(srt, SubtitleFormat.SRT, SubtitleFormat.VTT)
        assertTrue(converted is app.vidmatex.core.common.AppResult.Success)
        assertTrue((converted as app.vidmatex.core.common.AppResult.Success).value.startsWith("WEBVTT"))
    }
}
