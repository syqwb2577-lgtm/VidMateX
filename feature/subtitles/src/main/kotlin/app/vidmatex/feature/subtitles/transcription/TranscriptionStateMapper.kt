package app.vidmatex.feature.subtitles.transcription

/**
 * Plain mirror of WorkManager's run states (kept Android-free so the state machine
 * transitions are unit-testable). The ViewModel converts `WorkInfo.State` into this
 * enum and maps it through [TranscriptionStateMapper].
 */
enum class WorkerRunState {
    ENQUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    BLOCKED,
    INFO,
}

/**
 * Pure transition table for the transcription UI state. Cancellation always lands on
 * [TranscriptionState.CANCELED]; a SUCCEEDED worker without usable output downgrades
 * to FAILED rather than pretending success (no fake completion).
 */
object TranscriptionStateMapper {
    fun map(
        runState: WorkerRunState,
        previous: TranscriptionState,
        succeededWithOutput: Boolean = true,
    ): TranscriptionState = when (runState) {
        WorkerRunState.ENQUEUED, WorkerRunState.BLOCKED -> TranscriptionState.QUEUED
        WorkerRunState.RUNNING -> TranscriptionState.RUNNING
        WorkerRunState.SUCCEEDED ->
            if (succeededWithOutput) TranscriptionState.SUCCESS else TranscriptionState.FAILED
        WorkerRunState.FAILED -> TranscriptionState.FAILED
        WorkerRunState.CANCELLED -> TranscriptionState.CANCELED
        WorkerRunState.INFO -> previous
    }
}
