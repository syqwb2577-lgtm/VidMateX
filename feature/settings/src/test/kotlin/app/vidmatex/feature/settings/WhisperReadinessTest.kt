package app.vidmatex.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [TranscriptionReadiness] (§10): endpoint missing/invalid blocks enabling
 * transcription; a missing key is a warning only; a valid endpoint with a key is ready.
 */
class WhisperReadinessTest {

    private val validHttps = "https://whisper.example.com/v1"
    private val invalidHttp = "http://whisper.example.com/infer"

    @Test
    fun blankEndpointWithoutKey_isBlockingAndNotReady() {
        val r = TranscriptionReadiness.evaluate("", keyPresent = false, ::validity)
        assertFalse(r.ready)
        assertTrue(r.blockingIssues.contains(ReadinessIssue.ENDPOINT_MISSING))
        assertTrue(r.warnings.contains(ReadinessIssue.KEY_ABSENT))
    }

    @Test
    fun blankEndpointWithKey_isStillBlocking() {
        val r = TranscriptionReadiness.evaluate("   ", keyPresent = true, ::validity)
        assertFalse(r.ready)
        assertEquals(ReadinessIssue.ENDPOINT_MISSING, r.blockingIssues.first())
    }

    @Test
    fun invalidNonLoopbackHttp_isBlocking() {
        val r = TranscriptionReadiness.evaluate(invalidHttp, keyPresent = true, ::validity)
        assertFalse(r.ready)
        assertTrue(r.blockingIssues.contains(ReadinessIssue.ENDPOINT_INVALID))
    }

    @Test
    fun validHttpsEndpointWithoutKey_readyWithOnlyKeyWarning() {
        val r = TranscriptionReadiness.evaluate(validHttps, keyPresent = false, ::validity)
        assertTrue(r.ready)
        assertTrue(r.blockingIssues.isEmpty())
        assertEquals(ReadinessIssue.KEY_ABSENT, r.warnings.first())
    }

    @Test
    fun validEndpointWithKey_isFullyReady() {
        val r = TranscriptionReadiness.evaluate(validHttps, keyPresent = true, ::validity)
        assertTrue(r.ready)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun loopbackHttpEndpoint_isAcceptedForDevelopment() {
        val r = TranscriptionReadiness.evaluate("http://127.0.0.1:8080/v1", keyPresent = false, ::validity)
        assertTrue(r.ready)
    }

    private fun validity(url: String): Boolean = WhisperProbeUrlValidator.isValidProbeUrl(url)
}
