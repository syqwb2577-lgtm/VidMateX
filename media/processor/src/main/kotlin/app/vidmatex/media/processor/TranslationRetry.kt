package app.vidmatex.media.processor

import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.min

/**
 * Pure retry helper for network-backed translation (LibreTranslate). ML Kit is on-device
 * and does not need network retry. Retries only on transient failures
 * ([IOException], [SocketTimeoutException], HTTP 429/5xx signalled via [TransientHttpException]);
 * everything else (malformed request, unsupported language) fails immediately so the user
 * sees the real error. Cancellation is propagated (never caught).
 *
 * Backoff: fixed base delay doubled each attempt, capped at [MAX_DELAY_MS]. Delay runs on
 * the caller's dispatcher via [delay] (imported lazily so this file stays Android-free).
 * Pure classification logic ([classify], [shouldRetry]) is JVM-unit-testable.
 */
object TranslationRetry {
    const val DEFAULT_MAX_ATTEMPTS = 3
    const val BASE_DELAY_MS = 500L
    const val MAX_DELAY_MS = 4_000L

    /** Signals a transient HTTP status (429 / 5xx) from a Retrofit/OkHttp call site. */
    class TransientHttpException(val code: Int, message: String) : IOException(message)

    enum class Kind { TRANSIENT, PERMANENT }

    fun classify(t: Throwable): Kind = when (t) {
        is SocketTimeoutException, is IOException -> Kind.TRANSIENT
        else -> Kind.PERMANENT
    }

    fun shouldRetry(t: Throwable): Boolean = classify(t) == Kind.TRANSIENT

    /** Delay before attempt [attempt] (0-based first attempt = 0). */
    fun delayMs(attempt: Int): Long =
        if (attempt <= 0) 0L else min(BASE_DELAY_MS * (1L shl (attempt - 1)), MAX_DELAY_MS)

    /**
     * Run [block] up to [maxAttempts] times, retrying on transient errors. On the final
     * failure, the last [Throwable] is thrown (caller maps to AppResult.Error). Cancellation
     * exceptions propagate immediately. [sleep] is injectable so tests don't wait.
     */
    suspend fun <T> withRetry(
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        block: suspend (attempt: Int) -> T,
    ): T {
        var last: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block(attempt)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                last = t
                if (!shouldRetry(t) || attempt == maxAttempts - 1) throw t
                sleep(delayMs(attempt + 1))
            }
        }
        throw last ?: IllegalStateException("retry exhausted without result")
    }
}
