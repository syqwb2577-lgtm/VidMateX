package app.vidmatex.media.processor

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure JVM tests for [TranslationRetry] classification and retry behavior. Uses an
 * injectable [sleep] so tests never wait on real delays. No Android.
 *
 * Status: COMPLETE_NOT_RUN.
 */
class TranslationRetryTest {
    @Test
    fun `classifies io and timeout as transient`() {
        assertEquals(
            TranslationRetry.Kind.TRANSIENT,
            TranslationRetry.classify(IOException("boom")),
        )
        assertEquals(
            TranslationRetry.Kind.TRANSIENT,
            TranslationRetry.classify(SocketTimeoutException("slow")),
        )
        assertEquals(
            TranslationRetry.Kind.TRANSIENT,
            TranslationRetry.classify(TranslationRetry.TransientHttpException(429, "rate")),
        )
    }

    @Test
    fun `classifies runtime and illegal state as permanent`() {
        assertEquals(
            TranslationRetry.Kind.PERMANENT,
            TranslationRetry.classify(RuntimeException("nope")),
        )
        assertEquals(
            TranslationRetry.Kind.PERMANENT,
            TranslationRetry.classify(IllegalArgumentException("bad lang")),
        )
    }

    @Test
    fun `delayMs grows exponentially and caps`() {
        assertEquals(0L, TranslationRetry.delayMs(0))
        assertEquals(TranslationRetry.BASE_DELAY_MS, TranslationRetry.delayMs(1))
        assertEquals(TranslationRetry.BASE_DELAY_MS * 2, TranslationRetry.delayMs(2))
        assertEquals(TranslationRetry.BASE_DELAY_MS * 4, TranslationRetry.delayMs(3))
        assertTrue(TranslationRetry.delayMs(10) <= TranslationRetry.MAX_DELAY_MS)
    }

    @Test
    fun `withRetry succeeds on second attempt after transient failure`() = runBlocking {
        var attempts = 0
        val sleeps = mutableListOf<Long>()
        val result = TranslationRetry.withRetry(
            maxAttempts = 3,
            sleep = { sleeps += it },
        ) { attempt ->
            attempts++
            if (attempt == 0) throw IOException("transient")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, attempts)
        assertEquals(1, sleeps.size)
        assertEquals(TranslationRetry.BASE_DELAY_MS, sleeps[0])
    }

    @Test
    fun `withRetry fails fast on permanent error without retrying`() = runBlocking {
        var attempts = 0
        val sleeps = mutableListOf<Long>()
        try {
            TranslationRetry.withRetry(maxAttempts = 3, sleep = { sleeps += it }) {
                attempts++
                throw IllegalArgumentException("permanent")
            }
            fail("expected exception")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals(1, attempts)
        assertTrue("no sleep on permanent failure", sleeps.isEmpty())
    }

    @Test
    fun `withRetry exhausts attempts and throws last transient error`() = runBlocking {
        var attempts = 0
        try {
            TranslationRetry.withRetry(maxAttempts = 2, sleep = {}) {
                attempts++
                throw SocketTimeoutException("slow")
            }
            fail("expected exception")
        } catch (e: SocketTimeoutException) {
            // expected
        }
        assertEquals(2, attempts)
    }

    @Test
    fun `withRetry propagates cancellation immediately`() = runBlocking {
        var attempts = 0
        try {
            TranslationRetry.withRetry(maxAttempts = 3, sleep = {}) {
                attempts++
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
            fail("expected cancellation")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `shouldRetry matches classify`() {
        assertTrue(TranslationRetry.shouldRetry(IOException("x")))
        assertFalse(TranslationRetry.shouldRetry(RuntimeException("x")))
    }
}
