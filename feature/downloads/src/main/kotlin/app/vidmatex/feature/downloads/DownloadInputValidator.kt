package app.vidmatex.feature.downloads

import app.vidmatex.core.common.Constants
import app.vidmatex.core.common.UrlValidator
import app.vidmatex.core.model.DownloadMode
import app.vidmatex.core.model.DownloadSpec
import app.vidmatex.core.model.SourceType
import java.net.URI

/**
 * Pure pre-fetch URL validation for the download system (no Android framework imports;
 * safe for plain JVM unit tests).
 *
 * Security rules (master requirements §2/§3/§7):
 *  - scheme allowlist: HTTPS only for direct media downloads (cleartext traffic is denied
 *    app-wide in `network_security_config.xml`; `http`, `file`, `javascript`, `data`,
 *    `content`, `ftp`, `ws(s)`, `sms`, `tel`, `intent` are rejected);
 *  - host present, no userinfo credentials embedded in the URL;
 *  - private / loopback / link-local / reserved hosts rejected (SSRF defense-in-depth via
 *    [UrlValidator.isLikelyPrivateHost]; full DNS resolution-based blocking stays on the
 *    backend resolver for platform links);
 *  - platform pages (YouTube / Facebook / TikTok) are **metadata-only** compliance targets:
 *    they must never be enqueued as downloads from this module;
 *  - invalid input yields a typed [Result] reason so the caller can surface a localized,
 *    user-visible error instead of enqueuing a fake/progress-less job.
 *
 * Subtitle-only specs that already carry serialized content perform no network fetch and
 * therefore bypass URL validation; a legacy subtitle-only spec (no content yet) must pass
 * the same network validation as media jobs.
 */
internal object DownloadInputValidator {

    enum class Reason {
        /** Validation passed. */
        OK,

        /** Empty / blank input. */
        BLANK,

        /** Exceeds [Constants.MAX_URL_LENGTH]. */
        TOO_LONG,

        /** Disallowed scheme (javascript:, file:, data:, ftp:, …). */
        BLOCKED_SCHEME,

        /** Non-HTTPS scheme for a direct download. */
        NON_HTTPS,

        /** Malformed URI (cannot be parsed). */
        MALFORMED,

        /** Parsed but missing a host. */
        NO_HOST,

        /** Loopback / private / link-local / reserved address. */
        PRIVATE_HOST,

        /** Recognized platform page; metadata-only by compliance policy. */
        PLATFORM_METADATA_ONLY,

        /** Host/source type this client handles neither as a direct file nor via a compliant adapter. */
        UNSUPPORTED_SOURCE,
    }

    data class Result(
        val reason: Reason,
        val canonicalUrl: String? = null,
        /** Permanent user-actionable failure; never retried automatically. */
        val recoverable: Boolean = false,
    ) {
        val ok: Boolean get() = reason == Reason.OK
    }

    private val BLOCKED_SCHEMES = setOf(
        "javascript", "file", "data", "content", "ftp", "ftps",
        "sms", "tel", "intent", "ws", "wss", "mailto", "blob",
    )

    /**
     * True when this spec performs a network fetch at download time. Subtitle-only jobs that
     * already carry serialized (preview/translated/shifted) content write local text only.
     */
    fun requiresNetworkFetch(spec: DownloadSpec): Boolean {
        if (spec.mode == DownloadMode.SUBTITLE_ONLY) {
            val hasSerializedContent = spec.subtitleContentOriginal != null ||
                spec.subtitleContentTranslated != null
            return !hasSerializedContent
        }
        return true
    }

    /** Validates the enqueue-time spec URL; bypasses network rules for local-content subtitles. */
    fun validate(spec: DownloadSpec): Result {
        if (!requiresNetworkFetch(spec)) return Result(Reason.OK)
        return validateDirectUrl(spec.metadata.sourceUrl, spec.metadata.sourceType)
    }

    /**
     * Validates a direct media URL / adapter-returned stream URL. Platform source types are
     * rejected outright even if a stream-looking string was attached to their metadata.
     */
    fun validateDirectUrl(rawUrl: String, sourceType: SourceType): Result {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return Result(Reason.BLANK)
        if (trimmed.length > Constants.MAX_URL_LENGTH) return Result(Reason.TOO_LONG, canonicalUrl = trimmed)

        if (sourceType == SourceType.YOUTUBE ||
            sourceType == SourceType.FACEBOOK ||
            sourceType == SourceType.TIKTOK
        ) {
            return Result(Reason.PLATFORM_METADATA_ONLY, canonicalUrl = trimmed)
        }
        if (sourceType == SourceType.UNKNOWN) {
            return Result(Reason.UNSUPPORTED_SOURCE, canonicalUrl = trimmed)
        }

        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return Result(Reason.MALFORMED, canonicalUrl = trimmed)
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme in BLOCKED_SCHEMES) {
            return Result(Reason.BLOCKED_SCHEME, canonicalUrl = trimmed)
        }
        if (scheme != "https") {
            return Result(Reason.NON_HTTPS, canonicalUrl = trimmed)
        }
        if (uri.userInfo != null) {
            return Result(Reason.MALFORMED, canonicalUrl = trimmed)
        }
        val host = uri.host?.lowercase().orEmpty()
        if (host.isBlank()) return Result(Reason.NO_HOST, canonicalUrl = trimmed)
        if (UrlValidator.isLikelyPrivateHost(host)) {
            return Result(Reason.PRIVATE_HOST, canonicalUrl = trimmed)
        }
        return Result(Reason.OK, canonicalUrl = UrlValidator.canonicalize(trimmed))
    }

    /** Validates a stream URL returned by an adapter (DIRECT_URL semantics; compliance checked earlier). */
    fun validateStreamUrl(url: String): Result = validateDirectUrl(url, SourceType.DIRECT_URL)
}
