package app.vidmatex.feature.subtitles.transcription

import app.vidmatex.media.processor.WhisperEndpointValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the public [WhisperEndpointValidator] (media/processor, owned file
 * SpeechToTextImpls.kt). Pure JVM logic over `java.net.URI`; no Android imports.
 *
 * Availability rule: non-blank, parseable URL with a host; `https` always valid; `http`
 * valid only for loopback development hosts; everything else invalid.
 *
 * Written but NOT executed in this environment (no Android SDK / Gradle; host JDK 11
 * while the project targets JVM 17). Status: COMPLETE_NOT_RUN — pending CI.
 */
class WhisperEndpointValidatorTest {

    @Test
    fun `blank or missing host is invalid`() {
        assertFalse(WhisperEndpointValidator.isValid(""))
        assertFalse(WhisperEndpointValidator.isValid("   "))
        assertFalse(WhisperEndpointValidator.isValid(null))
        assertFalse(WhisperEndpointValidator.isValid("https://"))
        assertFalse(WhisperEndpointValidator.isValid("not a url"))
        assertFalse(WhisperEndpointValidator.isValid("example.com"))
    }

    @Test
    fun `https endpoints with a host are valid regardless of provider`() {
        assertTrue(WhisperEndpointValidator.isValid("https://api.openai.com/v1"))
        assertTrue(WhisperEndpointValidator.isValid("https://whisper.example.com/"))
        assertTrue(WhisperEndpointValidator.isValid("https://whisper.example.com/v1/audio/transcriptions"))
        assertTrue(WhisperEndpointValidator.isValid("https://groq.example.com"))
    }

    @Test
    fun `plain http loopback development hosts are valid`() {
        assertTrue(WhisperEndpointValidator.isValid("http://localhost:8000"))
        assertTrue(WhisperEndpointValidator.isValid("http://127.0.0.1:8000/v1"))
        assertTrue(WhisperEndpointValidator.isValid("http://10.0.2.2:8000"))
        assertTrue(WhisperEndpointValidator.isValid("http://whisper.local:8000"))
    }

    @Test
    fun `whitespace-surrounded urls and deep transcription paths are accepted`() {
        assertTrue(WhisperEndpointValidator.isValid("  https://host.example.com/v1/audio/transcriptions  "))
        assertTrue(WhisperEndpointValidator.isValid("https://host.example.com/audio/transcriptions"))
        assertFalse(WhisperEndpointValidator.isValid("https://"))
        assertTrue(WhisperEndpointValidator.isValid("http://10.0.2.2:8000/v1/x")) // emulator loopback allowed
    }
}
