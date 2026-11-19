package app.vidmatex.media.processor

import app.vidmatex.core.model.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [WhisperChunkMerger] chunk-offset timestamp math. Each chunk is
 * transcribed on a timeline starting at 0; the merger shifts every cue of chunk N by
 * that chunk's nominal start on the source timeline. No Android / no FFmpeg.
 *
 * Status: COMPLETE_NOT_RUN.
 */
class WhisperChunkMergerTest {
    private fun cue(start: Long, end: Long, text: String) =
        SubtitleCue(index = 1, startMs = start, endMs = end, lines = listOf(text))

    @Test
    fun `offsetBy shifts both ends by the chunk offset preserving relative duration`() {
        val cues = listOf(cue(0L, 2500L, "a"), cue(3000L, 5000L, "b"))
        val shifted = WhisperChunkMerger.offsetBy(cues, 600_000L) // +10 min
        assertEquals(600_000L, shifted[0].startMs)
        assertEquals(602_500L, shifted[0].endMs)
        assertEquals(603_000L, shifted[1].startMs)
        assertEquals(605_000L, shifted[1].endMs)
        // Text and index untouched.
        assertEquals(listOf("a", "b"), shifted.map { it.lines[0] })
        assertEquals(listOf(1, 1), shifted.map { it.index })
    }

    @Test
    fun `offsetBy zero or negative is identity`() {
        val cues = listOf(cue(100L, 200L, "x"))
        assertEquals(cues, WhisperChunkMerger.offsetBy(cues, 0L))
        assertEquals(cues, WhisperChunkMerger.offsetBy(cues, -500L))
    }

    @Test
    fun `merge offsets each chunk by its source-timeline start`() {
        // Two 600 s chunks: chunk 0 offset 0, chunk 1 offset 600_000 ms.
        val chunk0 = listOf(cue(0L, 1000L, "c0a"), cue(5000L, 6000L, "c0b"))
        val chunk1 = listOf(cue(0L, 2000L, "c1a"), cue(3000L, 4000L, "c1b"))
        val merged = WhisperChunkMerger.merge(listOf(chunk0, chunk1), listOf(0L, 600_000L))
        assertEquals(4, merged.size)
        // Chunk 1 cues land at 600s + their local time.
        val c1a = merged.first { it.lines[0] == "c1a" }
        assertEquals(600_000L, c1a.startMs)
        assertEquals(602_000L, c1a.endMs)
        val c1b = merged.first { it.lines[0] == "c1b" }
        assertEquals(603_000L, c1b.startMs)
        // Chunk 0 cues keep their original timestamps.
        val c0a = merged.first { it.lines[0] == "c0a" }
        assertEquals(0L, c0a.startMs)
        assertEquals(1000L, c0a.endMs)
    }

    @Test
    fun `merge result is sorted and re-indexed monotonically`() {
        val chunk0 = listOf(cue(5000L, 6000L, "late"))
        val chunk1 = listOf(cue(1000L, 2000L, "early-but-offset"))
        // Chunk 1 offset 0 so its cue (1s) sorts before chunk0's (5s).
        val merged = WhisperChunkMerger.merge(listOf(chunk0, chunk1), listOf(0L, 0L))
        assertEquals("early-but-offset", merged[0].lines[0])
        assertEquals("late", merged[1].lines[0])
        assertEquals(1, merged[0].index)
        assertEquals(2, merged[1].index)
        assertTrue(merged.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
    }

    @Test
    fun `missing offsets default to zero`() {
        val chunk0 = listOf(cue(0L, 1000L, "a"))
        val chunk1 = listOf(cue(0L, 1000L, "b"))
        val merged = WhisperChunkMerger.merge(listOf(chunk0, chunk1), listOf(0L)) // only one offset
        assertEquals(2, merged.size)
        assertEquals(0L, merged.first { it.lines[0] == "b" }.startMs)
    }

    @Test
    fun `single chunk merge is just its cues ordered`() {
        val chunk = listOf(cue(3000L, 4000L, "second"), cue(0L, 1000L, "first"))
        val merged = WhisperChunkMerger.merge(listOf(chunk), listOf(0L))
        assertEquals(listOf("first", "second"), merged.map { it.lines[0] })
    }
}
