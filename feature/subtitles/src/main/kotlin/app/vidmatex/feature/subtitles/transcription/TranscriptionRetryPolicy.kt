package app.vidmatex.feature.subtitles.transcription

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Pure retry classification for the Whisper transcription worker (no Android imports).
 * WorkManager's exponential backoff is configured in [TranscriptionWork]; this object
 * only decides `Result.retry()` vs `Result.failure()`.
 *
 * Retryable (transient — server/network may succeed on a later attempt):
 *  - [java.io.IOException] / connection failures (incl. [java.net.UnknownHostException])
 *  - [SocketTimeoutException]
 *  - HTTP 429 Too Many Requests
 *  - HTTP 5xx server errors
 *
 * Permanent (user action required, no point retrying):
 *  - missing / unreadable / bad local file
 *  - HTTP 4xx except 429 (auth 401/403, invalid file 400, gone 404/410, range 413)
 *  - empty response body / zero recognizable cues
 *  - endpoint not configured
 *
 * The worker caps transient retries at [MAX_TRANSIENT_RETRIES] attempts.
 */
internal object TranscriptionRetryPolicy {
    /** Extra transient attempts after the first run (first run = attempt 0). */
    const val MAX_TRANSIENT_RETRIES = 3

    enum class Kind {
        NETWORK, TIMEOUT, RATE_LIMIT, SERVER_5XX,
        BAD_FILE, PERMANENT_4XX, EMPTY_RESPONSE, NO_CUES, UNCONFIGURED, UNKNOWN,
    }

    private val HTTP_CODE_RE = Regex("""HTTP\s*(\d{3})""", RegexOption.IGNORE_CASE)

    /** Extracts the numeric HTTP status from an error message like "…HTTP 503: …". */
    fun httpCode(message: String): Int? =
        HTTP_CODE_RE.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /** Classifies a worker/STT result message; [httpCode] overrides any code in the text. */
    fun classify(message: String, httpCode: Int? = null, recoverableFlag: Boolean = false): Kind {
        val code = httpCode ?: httpCode(message)
        if (code != null) {
            when {
                code == 429 -> return Kind.RATE_LIMIT
                code in 500..599 -> return Kind.SERVER_5XX
                code in 400..499 -> return Kind.PERMANENT_4XX
            }
        }
        val m = message.lowercase()
        return when {
            m.contains("not configured") || m.contains("endpoint not configured") ||
                m.contains("set a self-hosted") -> Kind.UNCONFIGURED
            m.contains("timed out") || m.contains("timeout") -> Kind.TIMEOUT
            m.contains("could not reach") || m.contains("unknownhost") ||
                m.contains("unable to resolve") || m.contains("no address associated") -> Kind.NETWORK
            m.contains("network error") || m.contains("connect") || m.contains("unreachable") -> Kind.NETWORK
            m.contains("not found") || m.contains("no such file") || m.contains("could not read") ||
                m.contains("no valid") || m.contains("unsupported") || m.contains("not a valid") -> Kind.BAD_FILE
            m.contains("empty response") -> Kind.EMPTY_RESPONSE
            m.contains("no recognizable cues") || m.contains("no valid srt cues") -> Kind.NO_CUES
            recoverableFlag -> Kind.NETWORK
            else -> Kind.UNKNOWN
        }
    }

    /** Classifies an exception thrown inside the worker (file IO, unexpected errors). */
    fun classifyThrowable(t: Throwable, httpCode: Int? = null): Kind = when (t) {
        is SocketTimeoutException -> Kind.TIMEOUT
        is java.net.UnknownHostException -> Kind.NETWORK
        is IOException -> Kind.NETWORK
        else -> classify(t.message.orEmpty(), httpCode)
    }

    fun shouldRetry(kind: Kind): Boolean = when (kind) {
        Kind.NETWORK, Kind.TIMEOUT, Kind.RATE_LIMIT, Kind.SERVER_5XX -> true
        else -> false
    }

    /** `CoroutineWorker.runAttemptCount` starts at 0; fail once retries are exhausted. */
    fun retryExhausted(runAttemptCount: Int): Boolean = runAttemptCount >= MAX_TRANSIENT_RETRIES
}
