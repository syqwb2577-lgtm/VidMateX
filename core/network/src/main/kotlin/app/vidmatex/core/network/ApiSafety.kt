package app.vidmatex.core.network

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.common.SecretTextRedactor
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * Typed backend errors. [safeApiCall] maps low-level exceptions to these (and to
 * [AppResult.Error] with a plain-language message). The sealed hierarchy exists so
 * callers that need fine-grained handling can pattern-match; most callers only need
 * the [AppResult.Error] message.
 *
 * Classification contract (CONTRACTS section 10):
 *  - [BackendException.Timeout]            — connect/read/write timeout.
 *  - [BackendException.NoNetwork]         — DNS failure, connection refused, unreachable.
 *  - [BackendException.Unauthorized]       — HTTP 401 (missing/invalid resolver key).
 *  - [BackendException.RateLimited]        — HTTP 429 (recoverable after backoff).
 *  - [BackendException.HttpError]          — other HTTP 4xx/5xx; [HttpError.code] is the status.
 *  - [BackendException.MalformedBody]       — malformed/unparseable JSON (typed error, never a crash).
 *  - [BackendException.InvalidRequest]     — malformed URL / illegal request argument.
 *  - [BackendException.Unknown]            — anything else.
 *
 * Secret hygiene: every message derived from exception text or server error bodies
 * passes through [SecretTextRedactor]; API keys/bearer tokens are never echoed into
 * user-facing errors.
 */
sealed class BackendException(message: String) : Exception(message) {
    /** Connect/read/write timeout. */
    class Timeout(message: String) : BackendException(message)
    /** No network, DNS failure, or server unreachable. */
    class NoNetwork(message: String) : BackendException(message)
    /** HTTP 4xx/5xx. [code] is the HTTP status. [serverErrorCode] is the backend's
     *  frozen `error.code` string (e.g. "SSRF_BLOCKED", "RATE_LIMITED") when present. */
    open class HttpError(val code: Int, message: String, val serverErrorCode: String? = null) : BackendException(message)
    /** HTTP 401 Unauthorized: missing or wrong resolver API key (user action required). */
    class Unauthorized(message: String) : HttpError(401, message)
    /** HTTP 429 Too Many Requests: recoverable after a backoff wait. */
    class RateLimited(message: String) : HttpError(429, message)
    /** Malformed URL or illegal argument. */
    class InvalidRequest(message: String) : BackendException(message)
    /** JSON that cannot be deserialized into the expected DTO (unknown shape / broken payload). */
    class MalformedBody(message: String) : BackendException(message)
    /** Anything else (e.g. unexpected transport failure). */
    class Unknown(message: String) : BackendException(message)
}

/**
 * Pure exception -> [BackendException] mapping. Extracted so it is unit-testable
 * without a network or Retrofit.
 */
fun toBackendException(e: Throwable): BackendException = when (e) {
    is BackendException -> e
    is HttpException -> {
        val code = e.code()
        when (code) {
            401 -> BackendException.Unauthorized(
                "Resolver rejected the API key (HTTP 401). Check the configured resolver key in Settings."
            )
            429 -> BackendException.RateLimited(
                "Rate limit reached (HTTP 429). Wait a moment and try again."
            )
            else -> {
                val body = e.response()?.errorBody()?.string().orEmpty()
                // Backend error shape: {"error":{"code":"...","message":"..."}} — surface the
                // human message when present (redacted), otherwise a generic HTTP-code message.
                val serverMsg = extractBackendErrorMessage(body)?.let { SecretTextRedactor.redact(it) }
                val serverCode = extractBackendErrorCode(body)
                BackendException.HttpError(
                    code,
                    serverMsg ?: "Server returned HTTP $code${if (code == 404) " (endpoint not found)" else ""}.",
                    serverErrorCode = serverCode,
                )
            }
        }
    }
    // kotlinx.serialization JsonDecodingException / SerializationException: malformed JSON
    // surfaces as a typed MalformedBody error instead of crashing the caller.
    is SerializationException -> BackendException.MalformedBody(
        "The resolver returned data that could not be parsed as expected. Check that the endpoint is a compatible VidMateX resolver."
    )
    is SocketTimeoutException -> BackendException.Timeout("Request timed out. Check your connection and try again.")
    is UnknownHostException -> BackendException.NoNetwork("No network connection or host not found. Check your internet access.")
    is ConnectException -> BackendException.NoNetwork("Could not connect to the server. Check your network and the endpoint URL.")
    is IOException -> BackendException.Unknown("Network error: ${SecretTextRedactor.redact(e.message ?: e.javaClass.simpleName)}")
    is IllegalArgumentException -> BackendException.InvalidRequest(
        "Invalid request: ${SecretTextRedactor.redact(e.message ?: "check the URL and parameters.")}"
    )
    else -> BackendException.Unknown(SecretTextRedactor.redact(e.message ?: "Unexpected error: ${e.javaClass.simpleName}"))
}

/** Best-effort extraction of {"error":{"message":"..."}} from an error body, without a Json dependency. */
private fun extractBackendErrorMessage(body: String): String? {
    if (body.isBlank()) return null
    val msgRe = Regex(""""message"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    val match = msgRe.find(body) ?: return null
    return match.groupValues[1]
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .ifBlank { null }
}

/** Best-effort extraction of {"error":{"code":"..."}} from an error body, without a Json dependency. */
private fun extractBackendErrorCode(body: String): String? {
    if (body.isBlank()) return null
    val codeRe = Regex(""""code"\s*:\s*"([A-Z_]+)"""")
    val match = codeRe.find(body) ?: return null
    return match.groupValues[1].ifBlank { null }
}

/**
 * Wraps a suspend Retrofit/OkHttp call and maps failures to [AppResult.Error] with
 * clear, user-facing messages. Recoverable = the user can fix it (network, timeout,
 * rate limit, endpoint config); non-recoverable = bad request, auth, malformed body,
 * or unexpected server state.
 */
suspend fun <T> safeApiCall(block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: Throwable) {
    val be = toBackendException(e)
    val recoverable = when (be) {
        is BackendException.Timeout,
        is BackendException.NoNetwork,
        is BackendException.RateLimited -> true
        is BackendException.Unauthorized -> false
        is BackendException.HttpError -> be.code in 408..429 || be.code >= 500
        is BackendException.InvalidRequest,
        is BackendException.MalformedBody,
        is BackendException.Unknown -> false
    }
    AppResult.Error(be.message ?: be.javaClass.simpleName, recoverable = recoverable)
}
