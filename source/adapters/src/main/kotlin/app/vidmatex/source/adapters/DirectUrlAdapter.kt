package app.vidmatex.source.adapters

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.common.Constants
import app.vidmatex.core.common.UrlValidator
import app.vidmatex.core.common.safeSuspend
import app.vidmatex.core.model.MediaKind
import app.vidmatex.core.model.MediaTrack
import app.vidmatex.core.model.SourceType
import app.vidmatex.core.model.SubtitleFormat
import app.vidmatex.core.model.SubtitleTrack
import app.vidmatex.core.model.VideoMetadata
import java.net.URI
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Direct video/audio URLs. No platform API — just an HTTPS HEAD to learn size and type.
 * This is the only adapter that works with zero backend configuration.
 *
 * Security: before any connection the host is checked against [UrlValidator.isLikelyPrivateHost]
 * to block direct downloads from loopback/private/reserved addresses (SSRF defense-in-depth).
 * Full DNS-based SSRF validation is the backend resolver's responsibility for platform links.
 */
class DirectUrlAdapter(private val okHttpClient: OkHttpClient) : SourceAdapter {
    override val sourceType: SourceType = SourceType.DIRECT_URL

    override fun matches(url: String): Boolean = UrlValidator.detectSource(url) == SourceType.DIRECT_URL

    override suspend fun resolve(url: String): AppResult<VideoMetadata> = safeSuspend {
        val canonical = UrlValidator.canonicalize(url)
        rejectPrivateHost(canonical)
        val contentType = probeContentType(canonical)
        val isVideo = contentType?.let { c -> Constants.VIDEO_MIME_PREFIXES.any { p -> c.startsWith(p) } } ?: true
        if (!isVideo && contentType?.let { c -> Constants.AUDIO_MIME_PREFIXES.any { p -> c.startsWith(p) } } != true) {
            error("URL does not point to a supported media type ($contentType).")
        }
        val rawName = canonical.substringAfterLast('/').substringBefore('?').ifBlank { "video" }
        VideoMetadata(
            sourceType = SourceType.DIRECT_URL,
            sourceUrl = canonical,
            sourceId = UUID.nameUUIDFromBytes(canonical.toByteArray()).toString(),
            title = SourceNameSanitizer.sanitizeDisplayTitle(rawName),
            author = null,
            durationSec = null,
            thumbnailUrl = null,
            description = contentType,
        )
    }

    /**
     * HEAD first; servers that reject HEAD (405/501) fall back to a ranged GET
     * (`Range: bytes=0-0`) that reads response headers only — the body is never buffered.
     * Header parsing itself lives in the pure companion helpers (unit tested).
     */
    private suspend fun probeContentType(canonical: String): String? {
        val head = Request.Builder().url(canonical).head().build()
        return okHttpClient.newCall(head).execute().use { resp ->
            if (resp.code == 405 || resp.code == 501) {
                val rangedGet = Request.Builder().url(canonical).get()
                    .header("Range", "bytes=0-0").build()
                return@use okHttpClient.newCall(rangedGet).execute().use { get ->
                    if (!get.isSuccessful && get.code != 206) error("URL returned HTTP ${get.code}")
                    get.header("Content-Type")
                }
            }
            if (!resp.isSuccessful) error("URL returned HTTP ${resp.code}")
            return resp.header("Content-Type")
        }
    }

    override suspend fun listFormats(metadata: VideoMetadata): AppResult<List<MediaTrack>> = safeSuspend {
        rejectPrivateHost(metadata.sourceUrl)
        val head = Request.Builder().url(metadata.sourceUrl).head().build()
        okHttpClient.newCall(head).execute().use { resp ->
            if (!resp.isSuccessful) error("URL returned HTTP ${resp.code}")
            val contentType = resp.header("Content-Type").orEmpty()
            val length = contentLengthBytes(resp.header("Content-Length"))
            // Accept-Ranges: bytes => the server supports range requests (resumable download).
            val resumable = resumableFromAcceptRanges(resp.header("Accept-Ranges"))
            val ext = extensionFromUrl(metadata.sourceUrl)
            qualityCandidates("direct-0", contentType, ext, length, resumable)
        }
    }

    override suspend fun listSubtitles(metadata: VideoMetadata): AppResult<List<SubtitleTrack>> =
        AppResult.Success(emptyList())

    override suspend fun fetchSubtitle(metadata: VideoMetadata, track: SubtitleTrack, format: SubtitleFormat): AppResult<String> =
        AppResult.Error("Direct URLs do not provide subtitles.")

    override suspend fun streamUrl(metadata: VideoMetadata, track: MediaTrack): AppResult<String> =
        AppResult.Success(metadata.sourceUrl)

    private fun rejectPrivateHost(url: String) {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        if (UrlValidator.isLikelyPrivateHost(host)) {
            throw IllegalArgumentException(
                "Direct downloads from private/local addresses are blocked for security reasons."
            )
        }
    }

    companion object {
        /** Pure: file extension from URL path (lowercased, query stripped). */
        fun extensionFromUrl(url: String): String =
            url.substringAfterLast('.', "mp4").substringBefore('?').lowercase()

        /** Pure: container label from extension (capped at 4 chars, default mp4). */
        fun containerFromExtension(ext: String): String = ext.takeIf { it.length <= 4 } ?: "mp4"

        /** Pure: strip MIME parameters (`video/mp4; codecs="avc1"` → `video/mp4`); blank → null. */
        fun mimeBase(contentType: String?): String? =
            contentType?.trim()?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

        /** Pure: audio vs video from content-type and extension. */
        fun kindFromContent(contentType: String, ext: String): MediaKind =
            if (mimeBase(contentType)?.startsWith("audio/") == true || ext in AUDIO_EXTENSIONS) MediaKind.AUDIO else MediaKind.VIDEO

        /** Pure: parse `Content-Length`; null when missing, malformed or negative. */
        fun contentLengthBytes(header: String?): Long? {
            val digits = header?.trim()?.replace(",", "") ?: return null
            val value = digits.toLongOrNull() ?: return null
            return value.takeIf { it >= 0L }
        }

        /** Pure: `Accept-Ranges: bytes` ⇒ resumable; absent/other values ⇒ false. */
        fun resumableFromAcceptRanges(header: String?): Boolean =
            header?.trim()?.contains("bytes", ignoreCase = true) == true

        /**
         * Pure: build the direct-URL quality candidate list. A plain progressive URL yields
         * exactly one track; adaptive manifests (DASH/HLS) are never fabricated here — the
         * compliant platform resolver supplies those only when explicitly configured.
         */
        fun qualityCandidates(
            trackId: String,
            contentType: String,
            ext: String,
            length: Long?,
            resumable: Boolean,
        ): List<MediaTrack> = listOf(
            MediaTrack(
                trackId = trackId,
                kind = kindFromContent(contentType, ext),
                container = containerFromExtension(ext),
                estimatedBytes = length,
                resumable = resumable,
            ),
        )

        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "wav", "aac", "ogg", "flac", "opus", "wma")
    }
}
