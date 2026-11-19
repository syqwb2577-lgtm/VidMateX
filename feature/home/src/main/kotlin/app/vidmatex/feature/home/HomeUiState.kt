package app.vidmatex.feature.home

import app.vidmatex.core.model.DownloadMode
import app.vidmatex.core.model.MediaTrack
import app.vidmatex.core.model.SourceType
import app.vidmatex.core.model.SubtitleTrack
import app.vidmatex.core.model.VideoMetadata

/**
 * Full UI state for the Home screen. Fields are nullable where "not yet resolved" is
 * meaningful; collections default to empty so the UI never needs null checks for lists.
 *
 * [audioOnly] carries the audio-only choice structurally: `core/model` DownloadMode gains
 * an additive AUDIO_ONLY value per _spec/CHANGES_REQUESTED.md; until then the Home screen
 * builds a spec with videoTrack = null and audioTrack set, which the worker recognizes.
 */
data class HomeUiState(
    val link: String = "",
    val detectedSource: SourceType? = null,
    val isLoading: Boolean = false,
    val metadata: VideoMetadata? = null,
    val videoTracks: List<MediaTrack> = emptyList(),
    val audioTracks: List<MediaTrack> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val selectedVideo: MediaTrack? = null,
    val selectedAudio: MediaTrack? = null,
    val selectedSubtitle: SubtitleTrack? = null,
    val mode: DownloadMode = DownloadMode.VIDEO_AND_SUBTITLE,
    val audioOnly: Boolean = false,
    val isEnqueuing: Boolean = false,
    val error: String? = null,
    val clipboardUrl: String? = null,
    val snackbarMessage: String? = null,
) {
    /** True when the current selection can produce a valid DownloadSpec. */
    val canDownload: Boolean
        get() = metadata != null && when {
            audioOnly -> selectedAudio != null
            mode == DownloadMode.VIDEO_ONLY -> selectedVideo != null
            mode == DownloadMode.SUBTITLE_ONLY -> selectedSubtitle != null
            else -> selectedVideo != null
        }
}
