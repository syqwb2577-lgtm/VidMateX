package app.vidmatex.feature.subtitles.transcription

import app.vidmatex.core.database.PendingTranscriptionKeys
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-module dedup-key contract: [PendingTranscriptionKeys.keyFor] (core/database, used
 * by DownloadWorker/EmbedWorker when inserting the durable pending row) MUST produce
 * byte-identical output to [TranscriptionDedup.keyFor] (feature/subtitles, used by the
 * AutoTranscribeObserver when enqueuing and by TranscriptionWorker when marking terminal
 * status). If these ever diverge, a pending row would never match its enqueued job and
 * would be re-enqueued forever — breaking idempotency. Pure JVM, no Android.
 */
class PendingTranscriptionKeysSyncTest {
    private val paths = listOf(
        "/sdcard/Movies/My Video.mp4",
        "/storage/emulated/0/Download/audio.m4a",
        "file:///sdcard/Movies/v.mp4",
        "content://media/external/video/media/42",
        "\\\\server\\share\\video.mkv",
        "  /sdcard//Movies//v.mp4/  ",
        "C:\\Users\\me\\Music\\song.mp3",
        "/data/data/app.vidmatex/files/transcriptions/x.srt",
    )

    @Test fun `core database keyFor matches feature subtitles keyFor for all path shapes`() {
        for (path in paths) {
            assertEquals(
                "dedup key mismatch for path: $path",
                TranscriptionDedup.keyFor(path),
                PendingTranscriptionKeys.keyFor(path),
            )
        }
    }

    @Test fun `both return blank for blank input`() {
        assertEquals(TranscriptionDedup.keyFor(""), PendingTranscriptionKeys.keyFor(""))
        assertEquals(TranscriptionDedup.keyFor("   "), PendingTranscriptionKeys.keyFor("   "))
        assertEquals("", PendingTranscriptionKeys.keyFor(""))
    }

    @Test fun `normalize matches TranscriptionDedup normalize`() {
        for (path in paths) {
            assertEquals(
                "normalize mismatch for path: $path",
                TranscriptionDedup.normalize(path),
                PendingTranscriptionKeys.normalize(path),
            )
        }
    }
}
