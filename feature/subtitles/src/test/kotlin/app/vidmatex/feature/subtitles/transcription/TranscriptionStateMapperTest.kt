package app.vidmatex.feature.subtitles.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure transition tests for [TranscriptionStateMapper] including cancellation.
 * WorkManager types are mirrored by [WorkerRunState]; no Android imports.
 */
class TranscriptionStateMapperTest {

    @Test
    fun `queued and blocked map to QUEUED`() {
        assertEquals(TranscriptionState.QUEUED, TranscriptionStateMapper.map(WorkerRunState.ENQUEUED, TranscriptionState.IDLE))
        assertEquals(TranscriptionState.QUEUED, TranscriptionStateMapper.map(WorkerRunState.BLOCKED, TranscriptionState.IDLE))
    }

    @Test
    fun `running maps to RUNNING`() {
        assertEquals(TranscriptionState.RUNNING, TranscriptionStateMapper.map(WorkerRunState.RUNNING, TranscriptionState.QUEUED))
    }

    @Test
    fun `success with output maps to SUCCESS, without output downgrades to FAILED`() {
        assertEquals(
            TranscriptionState.SUCCESS,
            TranscriptionStateMapper.map(WorkerRunState.SUCCEEDED, TranscriptionState.RUNNING, succeededWithOutput = true),
        )
        assertEquals(
            TranscriptionState.FAILED,
            TranscriptionStateMapper.map(WorkerRunState.SUCCEEDED, TranscriptionState.RUNNING, succeededWithOutput = false),
        )
    }

    @Test
    fun `failure maps to FAILED and cancellation maps to CANCELED`() {
        assertEquals(TranscriptionState.FAILED, TranscriptionStateMapper.map(WorkerRunState.FAILED, TranscriptionState.RUNNING))
        assertEquals(TranscriptionState.CANCELED, TranscriptionStateMapper.map(WorkerRunState.CANCELLED, TranscriptionState.RUNNING))
        assertEquals(TranscriptionState.CANCELED, TranscriptionStateMapper.map(WorkerRunState.CANCELLED, TranscriptionState.QUEUED))
    }

    @Test
    fun `info state keeps previous state`() {
        assertEquals(TranscriptionState.RUNNING, TranscriptionStateMapper.map(WorkerRunState.INFO, TranscriptionState.RUNNING))
        assertEquals(TranscriptionState.IDLE, TranscriptionStateMapper.map(WorkerRunState.INFO, TranscriptionState.IDLE))
    }
}
