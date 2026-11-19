package app.vidmatex.feature.subtitles

/**
 * Pure decision for the error-snackbar Retry action (G-3): pick the last operation that
 * can be safely re-run from the current snapshot. No Android / Compose / WorkManager
 * imports, so the mapping is covered by plain JVM unit tests.
 */
internal object SubtitlesRetryPlanner {
    enum class RetryTarget {
        /** Re-run link resolution (`SubtitlesViewModel.resolve()`). */
        RESOLVE_LINK,
        /** Re-fetch the currently selected subtitle track (`selectTrack(...)`). */
        REFETCH_TRACK,
        /** Re-run the enqueue/download flow (`download()`). */
        DOWNLOAD,
        /** Re-run the retained transcription job (`retryTranscription()`). */
        RETRY_TRANSCRIPTION,
        /** No identifiable operation; just dismiss the error. */
        NONE,
    }

    /**
     * @param inputUrl non-blank when the user pasted/entered a link
     * @param hasSelectedTrack a subtitle track (or transcription track) is selected
     * @param hasCues the preview cue list is non-empty
     * @param canDownload the existing ViewModel gate for enqueueing the spec
     * @param transcriptionFailed a Whisper job ended FAILED (retryable when a local path remains)
     * @param transcriptionHasPath retained local audio/video path for retry
     */
    fun target(
        inputUrl: String,
        hasSelectedTrack: Boolean,
        hasCues: Boolean,
        canDownload: Boolean,
        transcriptionFailed: Boolean = false,
        transcriptionHasPath: Boolean = false,
    ): RetryTarget = when {
        // A failed transcription with a retained local file: re-run that job first.
        transcriptionFailed && transcriptionHasPath -> RetryTarget.RETRY_TRANSCRIPTION
        // A selected track whose fetch/parse/preview failed: refetch that track first.
        hasSelectedTrack && !hasCues -> RetryTarget.REFETCH_TRACK
        // Enqueue/serialization failure with a valid selection: re-run download().
        canDownload -> RetryTarget.DOWNLOAD
        // Resolution failure with an entered link: re-run resolve().
        inputUrl.isNotBlank() -> RetryTarget.RESOLVE_LINK
        // Nothing identifiable (no link, no selection): clear the error only.
        else -> RetryTarget.NONE
    }
}
