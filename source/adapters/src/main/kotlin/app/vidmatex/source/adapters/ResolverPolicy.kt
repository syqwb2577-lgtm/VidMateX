package app.vidmatex.source.adapters

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.common.UrlValidator
import app.vidmatex.core.model.SourceType

/**
 * Compliance posture for platform adapters (YouTube / Facebook / TikTok). The device app
 * performs METADATA resolution only through the optional, operator-configured backend:
 *  - no in-app scraping of platform pages;
 *  - no yt-dlp execution on device;
 *  - no direct-media URLs fabricated from page HTML;
 *  - `downloadAvailable` stays false on the device side even when a backend payload
 *    describes formats; the actual stream URL must come from the backend's expiring
 *    resolver response, and operators bear ToS/copyright responsibility for that branch.
 */
object SourceCompliance {
    /** Device-side platform adapters never offer a direct download. */
    const val PLATFORM_DOWNLOAD_AVAILABLE: Boolean = false

    /** Page scraping / on-device extraction are prohibited. */
    const val SCRAPING_ALLOWED: Boolean = false

    /** Human-readable compliance note surfaced for each platform type. */
    fun note(type: SourceType): String = when (type) {
        SourceType.YOUTUBE ->
            "YouTube metadata only. Downloads require the operator-configured compliant resolver; the app never scrapes YouTube pages."
        SourceType.FACEBOOK ->
            "Facebook metadata only. Downloads require the operator-configured compliant resolver; the app never scrapes Facebook pages."
        SourceType.TIKTOK ->
            "TikTok metadata only. Downloads require the operator-configured compliant resolver; the app never scrapes TikTok pages."
        SourceType.DIRECT_URL ->
            "Direct media URLs only; private/loopback hosts are blocked before any request."
        SourceType.UNKNOWN ->
            "This source is not supported."
    }
}

/**
 * Typed resolver reachability states. Pure mapping from HTTP status codes and exception
 * class names (no Retrofit/OkHttp imports, so it runs on the plain JVM). The production
 * typed hierarchy `core:network` `BackendException` (Unauthorized 401, RateLimited 429,
 * Timeout, NoNetwork, HttpError) is what `safeApiCall` raises; this mirror exists so
 * adapter-policy and selection logic can be unit tested without network classes.
 */
sealed class ResolverState(val recoverable: Boolean) {
    /** No backend URL configured in Settings. */
    object NotConfigured : ResolverState(true)
    /** Malformed / non-https resolver URL. */
    object InvalidUrl : ResolverState(true)
    /** HTTP 401 / 403 — API key missing or rejected. */
    object Unauthorized : ResolverState(true)
    /** HTTP 408 / 429 — rate limited or request timed out at the server. */
    object RateLimited : ResolverState(true)
    /** Client-side socket/read timeout. */
    object Timeout : ResolverState(true)
    /** DNS failure / connection refused / offline. */
    object NoNetwork : ResolverState(true)
    /** HTTP 5xx — backend failure; not retryable from the device alone. */
    data class ServerError(val code: Int) : ResolverState(false)
    /** Successful response. */
    object Ok : ResolverState(false)
    /** Anything unmapped. */
    data class Unknown(val detail: String) : ResolverState(false)

    companion object {
        /** Map an HTTP status code to a typed state. */
        fun http(code: Int): ResolverState = when (code) {
            in 200..299 -> Ok
            401, 403 -> Unauthorized
            408, 429 -> RateLimited
            in 500..599 -> ServerError(code)
            in 400..499 -> InvalidUrl
            else -> Unknown("HTTP $code")
        }

        /** Map a thrown exception's simple class name (pure; no network class references). */
        fun throwableName(name: String?): ResolverState = when (name) {
            "SocketTimeoutException", "TimeoutCancellationException", "TimeoutException" -> Timeout
            "UnknownHostException", "ConnectException", "HttpTimeoutException" -> NoNetwork
            "HttpException" -> Unknown("HTTP error response")
            else -> Unknown(name ?: "unexpected network error")
        }
    }
}

/**
 * Pure adapter-selection logic (JVM-testable with fakes). The device-facing
 * [MediaSourceResolver.adapterFor] keeps the Android `Patterns.WEB_URL` validity gate and
 * delegates host matching here, so selection rules do not depend on `android.util`.
 */
object AdapterSelection {
    fun select(url: String, adapters: List<SourceAdapter>): AppResult<SourceAdapter> {
        if (url.isBlank()) {
            return AppResult.Error("That doesn't look like a valid link.", recoverable = true)
        }
        val normalized = UrlValidator.normalize(url.trim())
        val adapter = adapters.firstOrNull { it.matches(normalized) }
            ?: return AppResult.Error(
                "This source is not supported. Only YouTube, Facebook, TikTok, and direct video links are handled.",
                recoverable = true,
            )
        return AppResult.Success(adapter)
    }
}
