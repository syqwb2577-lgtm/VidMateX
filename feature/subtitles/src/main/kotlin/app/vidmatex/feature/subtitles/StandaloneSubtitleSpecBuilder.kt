package app.vidmatex.feature.subtitles

import app.vidmatex.core.model.SourceType
import app.vidmatex.core.model.VideoMetadata
import java.util.UUID

/**
 * C4 (CHANGES_REQUESTED.md §C4): pure builder for the minimal synthesized metadata used
 * when a standalone transcription/export has cues but no resolved video link.
 *
 * Rules:
 *  - display title = picked file name without extension, falling back to "Transcription";
 *  - `sourceUrl` = the local absolute path when attached, otherwise a synthetic
 *    `local://transcription/<jobId>` identifier — a remote URL is NEVER invented;
 *  - `sourceType = SourceType.DIRECT_URL` (the subtitle-only worker path).
 *
 * Android-free (string parsing + `java.util.UUID` only) so the mapping is covered by
 * plain JVM unit tests.
 */
internal object StandaloneSubtitleSpecBuilder {
    const val FALLBACK_TITLE = "Transcription"
    private const val LOCAL_SCHEME_PREFIX = "local://transcription/"

    /** Display title: file name (without extension) of the picked file, else fallback. */
    fun title(sourcePath: String?, fallback: String = FALLBACK_TITLE): String {
        val name = sourcePath
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.trim()
            .orEmpty()
        return name.ifBlank { fallback.ifBlank { FALLBACK_TITLE } }
    }

    /**
     * Source URL stored on the synthesized spec: the local absolute path when a file is
     * attached; otherwise a synthetic `local://transcription/<jobId>` identifier.
     */
    fun sourceUrl(sourcePath: String?, jobId: String?): String {
        if (!sourcePath.isNullOrBlank()) return sourcePath
        val id = jobId?.trim().orEmpty().ifBlank { UUID.randomUUID().toString() }
        return LOCAL_SCHEME_PREFIX + id
    }

    /** Minimal metadata for the subtitle-only enqueue; no remote resolution fields invented. */
    fun buildMetadata(sourcePath: String?, jobId: String?): VideoMetadata {
        val displayTitle = title(sourcePath)
        return VideoMetadata(
            sourceType = SourceType.DIRECT_URL,
            sourceUrl = sourceUrl(sourcePath, jobId),
            sourceId = "standalone-transcription",
            title = displayTitle,
            author = null,
            durationSec = null,
            thumbnailUrl = null,
            description = "Standalone transcription export",
        )
    }
}
