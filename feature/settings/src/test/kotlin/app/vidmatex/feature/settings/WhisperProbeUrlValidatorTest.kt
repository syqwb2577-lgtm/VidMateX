package app.vidmatex.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [WhisperProbeUrlValidator] (C5): HTTPS always accepted; HTTP accepted
 * only for loopback development hosts; non-loopback HTTP and other schemes rejected.
 * No Android imports.
 *
 * Written but NOT executed (no Android SDK / Gradle in this static environment).
 * Status: COMPLETE_NOT_RUN — pending CI (`./gradlew :feature:settings:testDebugUnitTest`).
 */
class WhisperProbeUrlValidatorTest {

    @Test
    fun httpsEndpoint_anyHost_accepted() {
        assertTrue(WhisperProbeUrlValidator.isValidProbeUrl("https://api.whisper.example/health"))
        assertTrue(WhisperProbeUrlValidator.isValidProbeUrl("https://198.51.100.7:8443/infer"))
    }

    @Test
    fun httpLoopbackHosts_accepted_forDev() {
        assertTrue(WhisperProbeUrlValidator.isValidProbeUrl("http://localhost:8080/infer"))
        assertTrue(WhisperProbeUrlValidator.isValidProbeUrl("http://127.0.0.1:9000"))
        assertTrue(WhisperProbeUrlValidator.isValidProbeUrl("http://0.0.0.0:9000"))
        assertTrue(WhisperProbeUrlValidator.isValidProbeUrl("http://10.0.2.2:8000/asr"))
        assertTrue(WhisperProbeUrlValidator.isValidProbeUrl("http://whisper.local:9000"))
        assertTrue(WhisperProbeUrlValidator.isValidProbeUrl("http://asr.service.local"))
        assertTrue(WhisperProbeUrlValidator.isValidProbeUrl("http://[::1]:9000"))
    }

    @Test
    fun httpNonLoopbackHost_rejected() {
        assertFalse(WhisperProbeUrlValidator.isValidProbeUrl("http://api.example.com/infer"))
        assertFalse(WhisperProbeUrlValidator.isValidProbeUrl("http://192.168.1.10:9000"))
        assertFalse(WhisperProbeUrlValidator.isValidProbeUrl("http://8.8.8.8:8080"))
    }

    @Test
    fun nonHttpSchemes_rejected() {
        assertFalse(WhisperProbeUrlValidator.isValidProbeUrl("ftp://localhost:21"))
        assertFalse(WhisperProbeUrlValidator.isValidProbeUrl("ws://localhost:9000"))
        assertFalse(WhisperProbeUrlValidator.isValidProbeUrl("file:///etc/whisper.conf"))
    }

    @Test
    fun blankOrMalformed_rejected() {
        assertFalse(WhisperProbeUrlValidator.isValidProbeUrl(null))
        assertFalse(WhisperProbeUrlValidator.isValidProbeUrl(""))
        assertFalse(WhisperProbeUrlValidator.isValidProbeUrl("   "))
        assertFalse(WhisperProbeUrlValidator.isValidProbeUrl("not a url"))
        assertFalse(WhisperProbeUrlValidator.isValidProbeUrl("https://"))
    }

    @Test
    fun httpSubdomainOfLocalhost_accepted() {
        assertTrue(WhisperProbeUrlValidator.isValidProbeUrl("http://whisper.localhost:9000/infer"))
    }

    @Test
    fun httpHostEndingWithLocalButNotLocalhost_rejected() {
        // "mylocal" does not end with ".localhost"; plain http on a non-loopback host is rejected.
        assertFalse(WhisperProbeUrlValidator.isValidProbeUrl("http://mylocal:9000/infer"))
    }

    @Test
    fun httpsWithUserInfo_stillAcceptedByThisValidator_credentialsStrippedElsewhere() {
        // This validator only enforces scheme+host; userinfo is rejected at the fetch layer.
        assertTrue(WhisperProbeUrlValidator.isValidProbeUrl("https://api.whisper.example/v1"))
    }
}
