package app.vidmatex.feature.subtitles.transcription

import app.vidmatex.core.model.DownloadItem
import app.vidmatex.core.model.DownloadState

/**
 * Bridge between the downloads module's public API and the Whisper picker flow.
 *
 * The downloads module exposes `DownloadManager.observeAll()` (Flow<List<DownloadItem>>)
 * and each item's nullable `filePath`; there is no dedicated "completed file ready"
 * callback (recorded as a cross-module follow-up). This pure helper selects the newest
 * COMPLETED item whose local path looks like audio or a media container with an audio
 * track; the ViewModel surfaces it as a one-tap transcription source. No network, no
 * Android imports, no Room types.
 */
object CompletedAudioHook {
    /** Audio containers plus video containers Whisper can transcribe the audio from. */
    private val AUDIO_LIKE_EXTS = setOf(
        "mp3", "m4a", "aac", "wav", "opus", "ogg", "flac", "m4b",
        "mp4", "mkv", "mov", "webm", "3gp",
    )

    fun audioLike(path: String?): Boolean {
        val ext = path?.substringAfterLast('.')?.lowercase().orEmpty()
        return ext in AUDIO_LIKE_EXTS
    }

    /** Newest completed item with a usable local audio/media path; null when none. */
    fun pickLatest(items: List<DownloadItem>): DownloadItem? =
        items
            .filter { it.state == DownloadState.COMPLETED && !it.filePath.isNullOrBlank() && audioLike(it.filePath) }
            .maxByOrNull { it.createdAtMs }
}
