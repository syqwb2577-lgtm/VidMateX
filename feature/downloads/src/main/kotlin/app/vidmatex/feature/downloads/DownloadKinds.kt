package app.vidmatex.feature.downloads

import app.vidmatex.core.model.DownloadMode
import app.vidmatex.core.model.MediaTrack

/**
 * Pure (JVM-testable) media-kind helpers for download jobs.
 *
 * Audio-only detection: `core/model` `DownloadMode.AUDIO_ONLY` exists (additive enum value)
 * and `DownloadSpec.audioOnly` mirrors it. Explicit mode/flag win; when both are absent
 * (older serialized specs) audio-only falls back to the structural rule
 * `videoTrack == null && audioTrack != null`. All workers, the enqueue path and tests
 * share the rules from this one file.
 */
internal object DownloadKinds {
    const val KIND_VIDEO = "video"
    const val KIND_AUDIO = "audio"
    const val KIND_SUBTITLE = "subtitle"

    /** Structural audio-only predicate (compatible with or without a DownloadMode.AUDIO_ONLY value). */
    fun isAudioOnly(videoTrack: MediaTrack?, audioTrack: MediaTrack?): Boolean =
        videoTrack == null && audioTrack != null

    /** Persisted/resumable-map key segment for the primary stream of a job. */
    fun primaryKind(videoTrack: MediaTrack?, audioTrack: MediaTrack?): String =
        if (isAudioOnly(videoTrack, audioTrack)) KIND_AUDIO else KIND_VIDEO

    /**
     * Kind label stored on the Room row and used for kind-aware dedup:
     *  - [DownloadMode.SUBTITLE_ONLY] → [KIND_SUBTITLE];
     *  - explicit [DownloadMode.AUDIO_ONLY] or the `audioOnly` flag → [KIND_AUDIO];
     *  - otherwise the structural [primaryKind] (video vs structural audio-only old specs).
     */
    fun kindFor(
        mode: DownloadMode,
        audioOnlyFlag: Boolean,
        videoTrack: MediaTrack?,
        audioTrack: MediaTrack?,
    ): String = when {
        mode == DownloadMode.SUBTITLE_ONLY -> KIND_SUBTITLE
        mode == DownloadMode.AUDIO_ONLY || audioOnlyFlag -> KIND_AUDIO
        else -> primaryKind(videoTrack, audioTrack)
    }

    /**
     * Final file extension for an audio-only job derived from the track container.
     * Containers seen from resolvers: mp3, m4a, aac, ogg, opus, wav, flac, webm, mp4.
     */
    fun audioExtension(container: String?): String = when (container?.lowercase()?.trim()) {
        "mp3" -> "mp3"
        "m4a", "aac", "mp4a", "mp4" -> "m4a"
        "ogg", "oga", "opus" -> "ogg"
        "wav", "wave" -> "wav"
        "flac" -> "flac"
        "webm" -> "webm"
        null, "" -> "m4a"
        else -> container.lowercase().trim().take(4).ifBlank { "m4a" }
    }

    /** Heuristic: does a UI quality label look like an audio track (vs "720p")? */
    fun looksLikeAudioLabel(label: String?): Boolean {
        if (label.isNullOrBlank()) return false
        return label.uppercase() in setOf("MP3", "M4A", "AAC", "OGG", "OPUS", "WAV", "FLAC", "WEBM")
    }
}
