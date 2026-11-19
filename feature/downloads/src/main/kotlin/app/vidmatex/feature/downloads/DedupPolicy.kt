package app.vidmatex.feature.downloads

import app.vidmatex.core.model.DownloadSpec

/**
 * Pure duplicate-enqueue logic. A duplicate = same canonical source URL AND same track
 * kind (video vs audio-only) AND same quality label. The DAO query matches URL + label;
 * kind parity is confirmed here from the (de)serialized spec.
 */
internal object DedupPolicy {
    fun isAudioOnly(spec: DownloadSpec): Boolean =
        DownloadKinds.isAudioOnly(spec.videoTrack, spec.audioTrack)

    fun sameKind(existingAudioOnly: Boolean, candidateAudioOnly: Boolean): Boolean =
        existingAudioOnly == candidateAudioOnly

    /** Quality label used both for display and for the DAO dedup query. */
    fun qualityLabel(spec: DownloadSpec): String =
        spec.videoTrack?.qualityLabel
            ?: spec.audioTrack?.qualityLabel
            ?: if (spec.mode.name == "SUBTITLE_ONLY") "subtitle" else "default"

    /** Stable in-process guard key preventing double enqueue from UI taps/retries. */
    fun guardKey(canonicalUrl: String, spec: DownloadSpec): String =
        "$canonicalUrl|${qualityLabel(spec)}|${isAudioOnly(spec)}"

    /**
     * Persisted-state dedup key: normalized (canonical) URL + track kind + quality label.
     * The DAO query matches URL + label + kind; this key is the pure counterpart used for
     * in-flight maps and tests. Normalization (tracking-param stripping, lowercased scheme/
     * host) happens in `UrlValidator.canonicalize` before the key is built.
     */
    fun dedupKey(canonicalUrl: String, qualityLabel: String, trackKind: String): String =
        "${canonicalUrl.trim().lowercase()}|$trackKind|$qualityLabel"
}
