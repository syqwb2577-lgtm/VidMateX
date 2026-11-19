package app.vidmatex.media.processor

import app.vidmatex.core.model.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SubtitleParserImpl.shift] invariants (JVM-pure): both ends move by the identical offset,
 * index and lines are never altered, zero offset is a no-op, negative offsets clamp at zero
 * and always leave endMs >= startMs. Pending CI execution.
 */
class SubtitleShiftIndexTest {
    private lateinit var parser: SubtitleParserImpl

    @Before
    fun setUp() {
        parser = SubtitleParserImpl()
    }

    private val cues = listOf(
        SubtitleCue(1, 1_000L, 4_000L, listOf("First")),
        SubtitleCue(2, 5_500L, 9_250L, listOf("Second", "line two")),
        SubtitleCue(3, 100L, 200L, listOf("Short")),
    )

    @Test
    fun `zero offset leaves every field unchanged`() {
        val shifted = parser.shift(cues, 0L)
        assertEquals(cues.size, shifted.size)
        cues.zip(shifted).forEach { (a, b) ->
            assertEquals(a.index, b.index)
            assertEquals(a.startMs, b.startMs)
            assertEquals(a.endMs, b.endMs)
            assertEquals(a.lines, b.lines)
        }
    }

    @Test
    fun `positive offset adds the same value to start and end of every cue`() {
        val shifted = parser.shift(cues, 2_500L)
        assertEquals(3, shifted.size)
        assertEquals(3_500L, shifted[0].startMs)
        assertEquals(6_500L, shifted[0].endMs)
        assertEquals(8_000L, shifted[1].startMs)
        assertEquals(11_750L, shifted[1].endMs)
        // index and lines untouched
        assertEquals(2, shifted[1].index)
        assertEquals(listOf("Second", "line two"), shifted[1].lines)
    }

    @Test
    fun `negative offset clamps at zero and keeps end not before start`() {
        val shifted = parser.shift(cues, -5_000L)
        assertTrue(shifted.all { it.startMs >= 0L })
        assertTrue(shifted.all { it.endMs >= it.startMs })
        assertEquals(0L, shifted[0].startMs)
        assertEquals(0L, shifted[0].endMs)
        assertEquals(0L, shifted[2].startMs)
        assertEquals(0L, shifted[2].endMs)
    }

    @Test
    fun `moderate negative offset applies identically to both ends without clamping`() {
        val shifted = parser.shift(cues.take(1), -500L)
        assertEquals(500L, shifted[0].startMs)
        assertEquals(3_500L, shifted[0].endMs)
        assertTrue(shifted[0].endMs >= shifted[0].startMs)
    }

    @Test
    fun `shift preserves cue count, index order and arabic lines verbatim`() {
        val arabic = listOf(
            SubtitleCue(7, 2_000L, 3_500L, listOf("مرحبا", "العالم")),
            SubtitleCue(8, 4_000L, 6_000L, listOf("نص آخر")),
        )
        val shifted = parser.shift(arabic, 1_000L)
        assertEquals(2, shifted.size)
        assertEquals(7, shifted[0].index)
        assertEquals(8, shifted[1].index)
        assertEquals(3_000L, shifted[0].startMs)
        assertEquals(4_500L, shifted[0].endMs)
        assertEquals(arabic[0].lines, shifted[0].lines)
        assertEquals(arabic[1].lines, shifted[1].lines)
    }
}
