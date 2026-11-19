package app.vidmatex.feature.downloads

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException

/**
 * Pure retry classification (no Android imports). WorkManager's exponential backoff
 * stays configured in `DownloadManagerImpl.enqueueWorker`; this object only decides
 * `Result.retry()` vs `Result.failure()`.
 *
 * Retryable (transient):
 *  - [java.io.IOException] network/connect failures
 *  - [SocketTimeoutException]
 *  - HTTP 429 Too Many Requests
 *  - HTTP 5xx server errors
 *
 * Non-retryable (permanent / user action required):
 *  - user/system cancellation
 *  - missing local files or missing selected stream
 *  - auth/permanent 4xx (401, 403, 404, 410, 416)
 *  - storage-full / SAF permission / unsupported format / resolver errors
 *
 * Embedding (FFmpeg) failures are additionally capped at [MAX_EMBED_RETRIES] retries
 * so a broken subtitle/container combination cannot loop forever.
 */
internal object RetryPolicy {
    /** Extra attempts after the first embed run (first run = attempt 0). */
    const val MAX_EMBED_RETRIES = 2

    enum class Kind {
        NETWORK, TIMEOUT, RATE_LIMIT, SERVER_5XX,
        PERMANENT_4XX, CANCELLED, MISSING_FILE, STORAGE, PERMISSION,
        UNSUPPORTED_FORMAT, RESOLVER, INTEGRITY, UNSUPPORTED_URL, UNKNOWN,
    }

    fun classify(throwable: Throwable, httpCode: Int? = null): Kind {
        if (throwable is CancellationException) return Kind.CANCELLED
        if (httpCode != null) {
            when {
                httpCode == 429 -> return Kind.RATE_LIMIT
                httpCode in 500..599 -> return Kind.SERVER_5XX
                httpCode in 400..499 -> return Kind.PERMANENT_4XX
            }
        }
        return when (throwable) {
            is SocketTimeoutException -> Kind.TIMEOUT
            is IOException -> Kind.NETWORK
            else -> classifyMessage(throwable.message.orEmpty())
        }
    }

    private fun classifyMessage(msg: String): Kind {
        val m = msg.lowercase()
        return when {
            m.contains("cancelled") || m.contains("canceled") -> Kind.CANCELLED
            m.contains("no stream") || m.contains("missing") || m.contains("selected quality") -> Kind.MISSING_FILE
            m.contains("enospc") || m.contains("no space") || m.contains("storage full") -> Kind.STORAGE
            m.contains("permission") || m.contains("denied") || m.contains("saf") -> Kind.PERMISSION
            m.contains("unsupported") || m.contains("not supported") || m.contains("not a valid media") -> Kind.UNSUPPORTED_FORMAT
            m.contains("integrity") || m.contains("corrupt") || m.contains("size mismatch") || m.contains("incomplete download") -> Kind.INTEGRITY
            m.contains("private address") || m.contains("not a valid link") || m.contains("invalid url") || m.contains("must use https") -> Kind.UNSUPPORTED_URL
            m.contains("resolver") || m.contains("could not be resolved") || m.contains("not handled") -> Kind.RESOLVER
            else -> Kind.UNKNOWN
        }
    }

    fun shouldRetry(kind: Kind): Boolean = when (kind) {
        Kind.NETWORK, Kind.TIMEOUT, Kind.RATE_LIMIT, Kind.SERVER_5XX -> true
        else -> false
    }
    fun shouldRetry(throwable: Throwable, httpCode: Int? = null): Boolean =
        shouldRetry(classify(throwable, httpCode))

    /** CoroutineWorker.runAttemptCount starts at 0; fail once retries beyond the first run are exhausted. */
    fun embedRetryExhausted(runAttemptCount: Int): Boolean = runAttemptCount >= MAX_EMBED_RETRIES
}
