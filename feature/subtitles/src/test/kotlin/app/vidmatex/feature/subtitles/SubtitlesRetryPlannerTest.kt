package app.vidmatex.feature.subtitles

import app.vidmatex.feature.subtitles.SubtitlesRetryPlanner.RetryTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests for [SubtitlesRetryPlanner] (G-3 snackbar Retry action mapping).
 * No Android / Compose imports.
 *
 * Written but NOT executed (no Android SDK / Gradle in this static environment).
 * Status: COMPLETE_NOT_RUN — pending CI (`./gradlew :feature:subtitles:testDebugUnitTest`).
 */
class SubtitlesRetryPlannerTest {

    @Test
    fun selectedTrackWithoutCues_refetchTrack() {
        assertEquals(
            RetryTarget.REFETCH_TRACK,
            SubtitlesRetryPlanner.target(
                inputUrl = "https://example.com/watch?v=1",
                hasSelectedTrack = true,
                hasCues = false,
                canDownload = false,
            ),
        )
    }

    @Test
    fun downloadGateOpen_download() {
        assertEquals(
            RetryTarget.DOWNLOAD,
            SubtitlesRetryPlanner.target(
                inputUrl = "https://example.com/watch?v=1",
                hasSelectedTrack = true,
                hasCues = true,
                canDownload = true,
            ),
        )
    }

    @Test
    fun standaloneCuesWithoutMetadata_downloads() {
        // C4: standalone transcription with cues and no resolved link.
        assertEquals(
            RetryTarget.DOWNLOAD,
            SubtitlesRetryPlanner.target(
                inputUrl = "",
                hasSelectedTrack = false,
                hasCues = true,
                canDownload = true,
            ),
        )
    }

    @Test
    fun enteredLinkWithoutSelection_resolve() {
        assertEquals(
            RetryTarget.RESOLVE_LINK,
            SubtitlesRetryPlanner.target(
                inputUrl = "  https://example.com/watch?v=1  ",
                hasSelectedTrack = false,
                hasCues = false,
                canDownload = false,
            ),
        )
    }

    @Test
    fun noLinkNoSelection_none() {
        assertEquals(
            RetryTarget.NONE,
            SubtitlesRetryPlanner.target(
                inputUrl = "",
                hasSelectedTrack = false,
                hasCues = false,
                canDownload = false,
                transcriptionFailed = false,
                transcriptionHasPath = false,
            ),
        )
    }

    @Test
    fun failedTranscriptionWithRetainedPath_retriesTranscriptionFirst() {
        assertEquals(
            RetryTarget.RETRY_TRANSCRIPTION,
            SubtitlesRetryPlanner.target(
                inputUrl = "",
                hasSelectedTrack = false,
                hasCues = false,
                canDownload = false,
                transcriptionFailed = true,
                transcriptionHasPath = true,
            ),
        )
    }

    @Test
    fun failedTranscriptionWithoutPath_fallsBackToNone() {
        assertEquals(
            RetryTarget.NONE,
            SubtitlesRetryPlanner.target(
                inputUrl = "",
                hasSelectedTrack = false,
                hasCues = false,
                canDownload = false,
                transcriptionFailed = true,
                transcriptionHasPath = false,
            ),
        )
    }
}
