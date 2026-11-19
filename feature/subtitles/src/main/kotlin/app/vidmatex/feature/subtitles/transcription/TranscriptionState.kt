package app.vidmatex.feature.subtitles.transcription

/**
 * UI state machine for the Whisper transcription job, driven by the ViewModel from
 * user actions and observed [androidx.work.WorkInfo] states. Android-free enum so it
 * can be referenced from pure tests if needed.
 *
 * IDLE            — no active job
 * AWAITING_FILE   — endpoint configured; waiting for the user to pick a local file
 * PREPARING_FILE  — URI copied to cache / local path attached; before enqueue
 * QUEUED          — worker enqueued (WorkManager ENQUEUED/BLOCKED)
 * RUNNING         — worker active; [progress]/[stage] data flowing
 * SUCCESS         — SRT parsed into cues; preview/translation/download enabled
 * FAILED          — permanent failure; friendly error exposed; retry allowed when a
 *                   local path is retained
 * CANCELED        — user cancelled; worker coroutine stopped and partial outputs
 *                   removed; a retry may re-enqueue the retained local path
 */
enum class TranscriptionState {
    IDLE,
    AWAITING_FILE,
    PREPARING_FILE,
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELED,
}
