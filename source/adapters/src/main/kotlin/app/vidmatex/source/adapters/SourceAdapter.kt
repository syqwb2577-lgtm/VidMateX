package app.vidmatex.source.adapters

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.model.DownloadSpec
import app.vidmatex.core.model.MediaTrack
import app.vidmatex.core.model.SubtitleTrack
import app.vidmatex.core.model.VideoMetadata

/**
 * Each supported platform is an independently maintained, compliant adapter.
 * Unsupported/unauthorized sources must never fall back to guessing; they return
 * AppResult.Error with a plain-language message.
 */
interface SourceAdapter {
    val sourceType: app.vidmatex.core.model.SourceType

    /** Host-pattern match. Must be cheap and side-effect free. */
    fun matches(url: String): Boolean

    /** Resolve metadata (title, duration, thumbnail). */
    suspend fun resolve(url: String): AppResult<VideoMetadata>

    /** List offered streams (video/audio). Only qualities actually available are returned. */
    suspend fun listFormats(metadata: VideoMetadata): AppResult<List<MediaTrack>>

    /** List offered subtitle tracks with kind (never mislabel automatic as human). */
    suspend fun listSubtitles(metadata: VideoMetadata): AppResult<List<SubtitleTrack>>

    /** Fetch raw subtitle text in the requested format. */
    suspend fun fetchSubtitle(metadata: VideoMetadata, track: SubtitleTrack, format: app.vidmatex.core.model.SubtitleFormat): AppResult<String>

    /** Resolve a direct, time-limited stream URL for a chosen track (or error if unavailable). */
    suspend fun streamUrl(metadata: VideoMetadata, track: MediaTrack): AppResult<String>

    /**
     * Assemble a DownloadSpec from user choices. Default impl is usually fine;
     * adapters may override to enforce platform-specific constraints.
     */
    suspend fun buildSpec(
        metadata: VideoMetadata,
        videoTrack: MediaTrack?,
        audioTrack: MediaTrack?,
        subtitleTrack: SubtitleTrack?,
        spec: DownloadSpec,
    ): AppResult<DownloadSpec> = AppResult.Success(spec)
}
