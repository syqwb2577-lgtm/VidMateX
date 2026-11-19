package app.vidmatex.feature.subtitles.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure orchestration-manifest tests for [TranscriptionChunkPlanner]. Native FFmpeg
 * splitting executes in `media:processor` on device; here only the queue/manifest
 * math (windows, overlap, offsets, unknown-duration blocked marker) is exercised.
 */
class TranscriptionChunkPlannerTest {

    @Test
    fun `unknown duration yields one blocked native-split segment`() {
        val plan = TranscriptionChunkPlanner.plan(durationMs = null)
        assertEquals(1, plan.size)
        assertFalse(plan.first().nativeSplitAvailable)
        assertEquals(0L, plan.first().offsetMs)
    }

    @Test
    fun `known duration produces fixed windows with overlap and monotonic offsets`() {
        // 25 minutes = 1,500,000 ms; 600 s windows; 2 s overlap.
        val plan = TranscriptionChunkPlanner.plan(durationMs = 1_500_000L)
        assertEquals(3, plan.size)
        assertEquals(0, plan[0].index)
        assertEquals(0L, plan[0].sliceStartMs)
        assertEquals(600_000L, plan[0].sliceEndMs)
        assertEquals(0L, plan[0].overlapMs)
        assertEquals(0L, plan[0].offsetMs)

        assertEquals(1, plan[1].index)
        assertEquals(598_000L, plan[1].sliceStartMs) // overlap pulled back
        assertEquals(1_200_000L, plan[1].sliceEndMs)
        assertEquals(2_000L, plan[1].overlapMs)
        assertEquals(600_000L, plan[1].offsetMs)

        assertEquals(2, plan[2].index)
        assertEquals(1_198_000L, plan[2].sliceStartMs)
        assertEquals(1_500_000L, plan[2].sliceEndMs) // clamped to duration
        assertEquals(1_200_000L, plan[2].offsetMs)

        assertTrue(plan.all { it.nativeSplitAvailable })
        val offsets = plan.map { it.offsetMs }
        assertEquals(offsets.sorted(), offsets)
    }

    @Test
    fun `short media fits one window`() {
        val plan = TranscriptionChunkPlanner.plan(durationMs = 30_000L)
        assertEquals(1, plan.size)
        assertEquals(30_000L, plan.first().sliceEndMs)
    }

    @Test
    fun `upload ceiling flags chunking above 24 MB`() {
        assertTrue(TranscriptionChunkPlanner.needsChunking(25L * 1024 * 1024))
        assertFalse(TranscriptionChunkPlanner.needsChunking(10L * 1024 * 1024))
        assertFalse(TranscriptionChunkPlanner.needsChunking(0L))
    }
}
