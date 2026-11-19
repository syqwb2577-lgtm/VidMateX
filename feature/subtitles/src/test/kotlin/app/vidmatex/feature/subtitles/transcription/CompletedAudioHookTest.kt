package app.vidmatex.feature.subtitles.transcription

import app.vidmatex.core.model.DownloadItem
import app.vidmatex.core.model.DownloadState
import app.vidmatex.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [CompletedAudioHook]: consumes the public `DownloadManager.observeAll()`
 * item shape (`DownloadItem.filePath`); selects the newest COMPLETED audio/media file.
 */
class CompletedAudioHookTest {

    private fun item(
        id: String,
        path: String?,
        state: DownloadState,
        createdAt: Long,
    ) = DownloadItem(
        id = id,
        sourceUrl = "local://hook/$id",
        sourceType = SourceType.DIRECT_URL,
        title = id,
        state = state,
        filePath = path,
        createdAtMs = createdAt,
    )

    @Test
    fun `audio-like extensions accepted, others rejected`() {
        assertTrue(CompletedAudioHook.audioLike("/dl/song.mp3"))
        assertTrue(CompletedAudioHook.audioLike("/dl/talk.m4a"))
        assertTrue(CompletedAudioHook.audioLike("/dl/clip.mp4"))
        assertTrue(CompletedAudioHook.audioLike("/dl/movie.mkv"))
        assertFalse(CompletedAudioHook.audioLike("/dl/report.txt"))
        assertFalse(CompletedAudioHook.audioLike(null))
    }

    @Test
    fun `picks newest completed audio item and ignores non-completed or non-media`() {
        val items = listOf(
            item("old", "/dl/older.mp3", DownloadState.COMPLETED, 1_000L),
            item("failed", "/dl/failed.mp3", DownloadState.FAILED, 5_000L),
            item("note", "/dl/note.txt", DownloadState.COMPLETED, 9_000L),
            item("latest", "/dl/latest.m4a", DownloadState.COMPLETED, 8_000L),
        )
        val picked = CompletedAudioHook.pickLatest(items)
        assertEquals("latest", picked?.id)
        assertEquals("/dl/latest.m4a", picked?.filePath)
    }

    @Test
    fun `null when nothing usable completed`() {
        assertNull(CompletedAudioHook.pickLatest(emptyList()))
        assertNull(
            CompletedAudioHook.pickLatest(
                listOf(item("queued", "/dl/x.mp3", DownloadState.QUEUED, 1L)),
            ),
        )
    }
}
