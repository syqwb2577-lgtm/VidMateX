package app.vidmatex.feature.subtitles

import app.vidmatex.core.model.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the per-cue editor state [CueEdits]: text-only, timestamps kept. */
class CueEditsTest {

    private fun cue(i: Int, start: Long, end: Long, vararg lines: String) =
        SubtitleCue(i, start, end, lines.toList())

    @Test
    fun `normalizeLines splits trims and drops blank lines`() {
        assertEquals(listOf("First", "Second"), CueEdits.normalizeLines(" First\n\nSecond \n"))
        assertTrue(CueEdits.normalizeLines("   \n  ").isEmpty())
    }

    @Test
    fun `override changes only lines, index and timestamps preserved`() {
        val cues = listOf(cue(1, 1000, 2000, "Old"), cue(2, 3000, 4000, "Keep"))
        val edited = CueEdits.apply(cues, mapOf(1 to listOf("New text", "Line 2")))
        assertEquals(listOf("New text", "Line 2"), edited[0].lines)
        assertEquals(1, edited[0].index)
        assertEquals(1000L, edited[0].startMs)
        assertEquals(2000L, edited[0].endMs)
        assertEquals(listOf("Keep"), edited[1].lines)
    }

    @Test
    fun `blank and missing overrides fall back to original lines`() {
        val cues = listOf(cue(1, 1000, 2000, "Original"))
        val edited = CueEdits.apply(cues, mapOf(1 to listOf("   "), 2 to listOf("Ignored")))
        assertEquals(listOf("Original"), edited[0].lines)
    }
}
