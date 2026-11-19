package app.vidmatex.media.processor

import app.vidmatex.core.model.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Tests for the sync-shift and language-detection helpers added to SubtitleParser. */
class SubtitleShiftAndLanguageTest {

    private lateinit var parser: SubtitleParserImpl

    @Before
    fun setUp() {
        parser = SubtitleParserImpl()
    }

    @Test
    fun `shift positive offset moves both timestamps`() {
        val cues = listOf(SubtitleCue(1, 1000L, 4000L, listOf("Hi")))
        val shifted = parser.shift(cues, 2500L)
        assertEquals(3500L, shifted[0].startMs)
        assertEquals(6500L, shifted[0].endMs)
    }

    @Test
    fun `shift negative offset clamps to zero and never goes negative`() {
        val cues = listOf(SubtitleCue(1, 1000L, 4000L, listOf("Hi")))
        val shifted = parser.shift(cues, -5000L)
        assertEquals(0L, shifted[0].startMs)
        assertEquals(0L, shifted[0].endMs)
    }

    @Test
    fun `detectLanguage returns ar for Arabic text`() {
        val arabic = listOf(SubtitleCue(1, 0L, 1000L, listOf("مرحبا بالعالم")))
        assertEquals("ar", parser.detectLanguage(arabic))
    }

    @Test
    fun `detectLanguage returns null for empty or latin-only cues`() {
        assertNull(parser.detectLanguage(emptyList()))
        val english = listOf(SubtitleCue(1, 0L, 1000L, listOf("Hello world")))
        assertNull(parser.detectLanguage(english))
    }

    @Test
    fun `shift preserves cue count and text`() {
        val cues = listOf(
            SubtitleCue(1, 0L, 1000L, listOf("A")),
            SubtitleCue(2, 2000L, 3000L, listOf("B")),
        )
        val shifted = parser.shift(cues, 500L)
        assertEquals(2, shifted.size)
        assertEquals(listOf("A"), shifted[0].lines)
        assertEquals(listOf("B"), shifted[1].lines)
        assertEquals(2500L, shifted[1].startMs)
    }
}
