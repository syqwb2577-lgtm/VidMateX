package app.vidmatex.feature.downloads

import app.vidmatex.core.model.MediaKind
import app.vidmatex.core.model.MediaTrack

/**
 * Pure quality/speed selection policy (no Android imports). The Home/Settings UI collects a
 * user-chosen track; this object owns the fallback ordering when the user has not picked a
 * track, when metadata is partial, or when the source exposes only a single best-effort
 * stream (master requirement §4: unknown/absent metadata degrades gracefully to one item).
 */
internal object FormatSelectionPolicy {

    /** Best video track: highest pixel height, then highest bitrate; null when none. */
    fun bestVideo(tracks: List<MediaTrack>): MediaTrack? =
        tracks.filter { it.kind == MediaKind.VIDEO }
            .sortedWith(compareByDescending<MediaTrack> { it.heightPx ?: 0 }
                .thenByDescending { it.bitrateBps ?: 0 })
            .firstOrNull()

    /** Best audio track: highest bitrate, then sample rate; null when none. */
    fun bestAudio(tracks: List<MediaTrack>): MediaTrack? =
        tracks.filter { it.kind == MediaKind.AUDIO }
            .sortedWith(compareByDescending<MediaTrack> { it.bitrateBps ?: 0 }
                .thenByDescending { it.sampleRate ?: 0 })
            .firstOrNull()

    /**
     * Graceful fallback for sparse metadata: any usable track, preferring video; never
     * synthesizes a URL or fabricates quality — it only orders what the adapter reported.
     */
    fun bestEffort(tracks: List<MediaTrack>): MediaTrack? =
        bestVideo(tracks) ?: bestAudio(tracks) ?: tracks.firstOrNull()

    /** True when at least one concrete stream track exists to download. */
    fun hasAnyTrack(video: MediaTrack?, audio: MediaTrack?): Boolean = video != null || audio != null
}
