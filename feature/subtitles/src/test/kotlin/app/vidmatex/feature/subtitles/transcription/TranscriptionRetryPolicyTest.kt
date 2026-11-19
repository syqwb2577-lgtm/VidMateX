package app.vidmatex.feature.subtitles.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Pure tests for [TranscriptionRetryPolicy] (no Android / WorkManager imports).
 *
 * Written but NOT executed in this environment (no Android SDK / Gradle; host JDK 11
 * while the project targets JVM 17). Status: COMPLETE_NOT_RUN — pending CI
 * (`./gradlew :feature:subtitles:testDebugUnitTest`).
 */
class TranscriptionRetryPolicyTest {

    @Test
    fun `httpCode parses three-digit status from server error message`() {
        assertEquals(503, TranscriptionRetryPolicy.httpCode("Transcription server returned HTTP 503: boom"))
        assertEquals(429, TranscriptionRetryPolicy.httpCode("...http 429..."))
        assertEquals(401, TranscriptionRetryPolicy.httpCode("HTTP 401: unauthorized"))
        assertNull(TranscriptionRetryPolicy.httpCode("no status code here"))
        assertNull(TranscriptionRetryPolicy.httpCode(""))
    }

    @Test
    fun `transient network timeout unknownhost and io exceptions are retryable`() {
        assertTrue(TranscriptionRetryPolicy.shouldRetry(
            TranscriptionRetryPolicy.classify("Transcription request timed out. Check your connection.")
        ))
        assertTrue(TranscriptionRetryPolicy.shouldRetry(
            TranscriptionRetryPolicy.classify("Could not reach the transcription server.")
        ))
        assertTrue(TranscriptionRetryPolicy.shouldRetry(
            TranscriptionRetryPolicy.classify("Network error during transcription: socket")
        ))
        assertTrue(TranscriptionRetryPolicy.shouldRetry(
            TranscriptionRetryPolicy.classifyThrowable(SocketTimeoutException("timeout"))
        ))
        assertTrue(TranscriptionRetryPolicy.shouldRetry(
            TranscriptionRetryPolicy.classifyThrowable(UnknownHostException("no host"))
        ))
        assertTrue(TranscriptionRetryPolicy.shouldRetry(
            TranscriptionRetryPolicy.classifyThrowable(IOException("connection reset"))
        ))
    }

    @Test
    fun `http 429 and 5xx retry while 4xx auth client errors do not`() {
        assertTrue(TranscriptionRetryPolicy.shouldRetry(
            TranscriptionRetryPolicy.classify("HTTP 429: slow down", httpCode = 429)
        ))
        assertTrue(TranscriptionRetryPolicy.shouldRetry(
            TranscriptionRetryPolicy.classify("HTTP 500: internal", httpCode = 500)
        ))
        assertTrue(TranscriptionRetryPolicy.shouldRetry(
            TranscriptionRetryPolicy.classify("HTTP 503: unavailable", httpCode = 503)
        ))
        assertFalse(TranscriptionRetryPolicy.shouldRetry(
            TranscriptionRetryPolicy.classify("HTTP 401: unauthorized", httpCode = 401)
        ))
        assertFalse(TranscriptionRetryPolicy.shouldRetry(
            TranscriptionRetryPolicy.classify("HTTP 403: forbidden", httpCode = 403)
        ))
        assertFalse(TranscriptionRetryPolicy.shouldRetry(
            TranscriptionRetryPolicy.classify("HTTP 404: not found", httpCode = 404)
        ))
        assertFalse(TranscriptionRetryPolicy.shouldRetry(
            TranscriptionRetryPolicy.classify("HTTP 400: bad file", httpCode = 400)
        ))
    }

    @Test
    fun `permanent outcomes are not retryable`() {
        val permanent = listOf(
            TranscriptionRetryPolicy.classify("Audio file not found: /tmp/x.mp3"),
            TranscriptionRetryPolicy.classify("Transcription server returned an empty response."),
            TranscriptionRetryPolicy.classify("Transcription server returned no recognizable cues."),
            TranscriptionRetryPolicy.classify("Speech-to-text is not configured. Set a self-hosted Whisper endpoint."),
            TranscriptionRetryPolicy.classify("unsupported media container"),
        )
        permanent.forEach { kind ->
            assertFalse("expected permanent but was $kind", TranscriptionRetryPolicy.shouldRetry(kind))
        }
    }

    @Test
    fun `recoverable flag upgrades an otherwise unknown error to network-retryable`() {
        assertTrue(TranscriptionRetryPolicy.shouldRetry(
            TranscriptionRetryPolicy.classify("something transient", recoverableFlag = true)
        ))
        assertFalse(TranscriptionRetryPolicy.shouldRetry(
            TranscriptionRetryPolicy.classify("something unknown", recoverableFlag = false)
        ))
    }

    @Test
    fun `transient retries capped at three attempts`() {
        assertFalse(TranscriptionRetryPolicy.retryExhausted(0))
        assertFalse(TranscriptionRetryPolicy.retryExhausted(1))
        assertFalse(TranscriptionRetryPolicy.retryExhausted(2))
        assertTrue(TranscriptionRetryPolicy.retryExhausted(3))
        assertTrue(TranscriptionRetryPolicy.retryExhausted(4))
    }

    @Test
    fun `413 payload and 402 are permanent client errors`() {
        assertFalse(
            TranscriptionRetryPolicy.shouldRetry(
                TranscriptionRetryPolicy.classify("HTTP 413 payload too large", httpCode = 413),
            ),
        )
        assertFalse(
            TranscriptionRetryPolicy.shouldRetry(
                TranscriptionRetryPolicy.classify("HTTP 402 payment required", httpCode = 402),
            ),
        )
    }

    @Test
    fun `httpCode tolerates lowercase scheme and absent codes`() {
        assertEquals(503, TranscriptionRetryPolicy.httpCode("http 503 boom"))
        assertNull(TranscriptionRetryPolicy.httpCode("server unavailable without code"))
    }
}
