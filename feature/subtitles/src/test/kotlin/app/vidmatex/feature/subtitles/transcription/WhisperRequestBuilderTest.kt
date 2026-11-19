package app.vidmatex.feature.subtitles.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the Whisper request contract ([WhisperRequestBuilder], [WhisperSecret],
 * [WhisperEndpointConfig]). No OkHttp, no Android, no network: the request shape
 * (URL normalization, multipart fields, conditional Authorization header, redacted
 * secret) is asserted on an immutable spec built from a fake config.
 */
class WhisperRequestBuilderTest {

    @Test
    fun `url normalization mirrors endpoint variants`() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            WhisperRequestBuilder.normalizeUrl("https://api.openai.com"),
        )
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            WhisperRequestBuilder.normalizeUrl("https://api.openai.com/"),
        )
        assertEquals(
            "https://host.example.com/v1/audio/transcriptions",
            WhisperRequestBuilder.normalizeUrl("https://host.example.com/v1"),
        )
        assertEquals(
            "https://host.example.com/v1/audio/transcriptions",
            WhisperRequestBuilder.normalizeUrl("https://host.example.com/v1/audio/transcriptions"),
        )
    }

    @Test
    fun `request carries file model response_format fields and filename`() {
        // Short synthetic fixture (below CI secret-scan length thresholds).
        val secret = WhisperSecret.from("sk-fixture-value")
        val spec = WhisperRequestBuilder.build(
            endpoint = "https://api.openai.com/v1",
            model = "whisper-1",
            secret = secret,
            fileName = "lecture.m4a",
            languageCode = null,
        )
        assertEquals("https://api.openai.com/v1/audio/transcriptions", spec.url)
        assertEquals("POST", spec.httpMethod)
        // File bytes are streamed by OkHttp; the spec carries only the field name + filename.
        val fileField = spec.fields.first { it.name == WhisperRequestBuilder.FILE_FIELD }
        assertEquals("", fileField.value)
        assertEquals("lecture.m4a", fileField.fileName)
        assertEquals("whisper-1", spec.field(WhisperRequestBuilder.MODEL_FIELD))
        // Production default is verbose_json (timed segments); matches the parser.
        assertEquals("verbose_json", spec.field(WhisperRequestBuilder.RESPONSE_FORMAT_FIELD))
        // Auto-detect: no language field.
        assertNull(spec.field(WhisperRequestBuilder.LANGUAGE_FIELD))
    }

    @Test
    fun `explicit language field is sent and normalized to two letters`() {
        val spec = WhisperRequestBuilder.build(
            endpoint = "https://host/v1",
            model = "small",
            secret = WhisperSecret.from(""),
            fileName = "a.mp3",
            languageCode = "AR",
        )
        assertEquals("ar", spec.field(WhisperRequestBuilder.LANGUAGE_FIELD))
    }

    @Test
    fun `authorization header present only when key exists and never leaks`() {
        // Short synthetic fixture (below CI secret-scan length thresholds).
        val key = "sk-fixture-key-123"
        val withKey = WhisperRequestBuilder.build(
            endpoint = "https://host/v1",
            model = "whisper-1",
            secret = WhisperSecret.from(key),
            fileName = "a.mp3",
        )
        val auth = withKey.headers.firstOrNull { it.first == "Authorization" }
        requireNotNull(auth)
        assertEquals("Bearer $key", auth.second)

        val withoutKey = WhisperRequestBuilder.build(
            endpoint = "https://host/v1",
            model = "whisper-1",
            secret = WhisperSecret.from(""),
            fileName = "a.mp3",
        )
        assertTrue(withoutKey.headers.none { it.first.equals("Authorization", ignoreCase = true) })

        // Redaction: neither toString nor the log summary may contain the raw key.
        assertFalse(WhisperSecret.from(key).toString().contains(key))
        assertFalse(withKey.redactedSummary().contains(key))
        assertFalse(withKey.fields.toString().contains(key))
    }

    @Test
    fun `default model used when model blank`() {
        val spec = WhisperRequestBuilder.build(
            endpoint = "https://host",
            model = "  ",
            secret = WhisperSecret.from(""),
            fileName = "a.wav",
        )
        assertEquals(WhisperRequestBuilder.DEFAULT_MODEL, spec.field(WhisperRequestBuilder.MODEL_FIELD))
    }

    @Test
    fun `verbose_json response format requested verbatim when chosen`() {
        val spec = WhisperRequestBuilder.build(
            endpoint = "https://host",
            model = "small",
            secret = WhisperSecret.from(""),
            fileName = "a.mp3",
            responseFormat = "verbose_json",
        )
        assertEquals("verbose_json", spec.field(WhisperRequestBuilder.RESPONSE_FORMAT_FIELD))
    }

    @Test
    fun `config validation blocks invalid endpoint and bad file, missing key is warning only`() {
        val blockedEndpoint = WhisperEndpointConfig(
            endpoint = "http://example.com/whisper",
            model = "whisper-1",
            secret = WhisperSecret.from("sk-x"),
        ).validate(fileExists = true, fileLength = 1024L)
        assertTrue(blockedEndpoint.any { it.severity == ConfigSeverity.BLOCKING && it.code == "invalid_endpoint" })

        val missingFile = WhisperEndpointConfig(
            endpoint = "https://host/v1",
            model = "whisper-1",
            secret = WhisperSecret.from("sk-x"),
        ).validate(fileExists = false, fileLength = 0L)
        assertTrue(missingFile.any { it.code == "missing_file" })

        val emptyFile = WhisperEndpointConfig(
            endpoint = "https://host/v1",
            model = "whisper-1",
            secret = WhisperSecret.from("sk-x"),
        ).validate(fileExists = true, fileLength = 0L)
        assertTrue(emptyFile.any { it.code == "empty_file" })

        val keylessHealthy = WhisperEndpointConfig(
            endpoint = "https://host/v1",
            model = "whisper-1",
            secret = WhisperSecret.from(""),
        ).validate(fileExists = true, fileLength = 2048L)
        assertTrue(keylessHealthy.none { it.severity == ConfigSeverity.BLOCKING })
        assertTrue(keylessHealthy.any { it.code == "missing_key" && it.severity == ConfigSeverity.WARNING })
    }
}
