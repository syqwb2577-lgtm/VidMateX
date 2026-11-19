package app.vidmatex.core.network

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tolerant deserialization of GET /health payloads: older/minimal backends that
 * omit `version` or `resolvers` must still deserialize to safe defaults.
 */
class HealthResponseTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test fun `minimal status-only json uses defaults`() {
        val decoded = json.decodeFromString<HealthResponse>("""{"status":"ok"}""")
        assertEquals("ok", decoded.status)
        assertNull(decoded.version)
        assertTrue(decoded.resolvers.isEmpty())
    }

    @Test fun `missing entire object fields deserialize to defaults`() {
        val decoded = json.decodeFromString<HealthResponse>("{}")
        assertEquals("", decoded.status)
        assertNull(decoded.version)
        assertTrue(decoded.resolvers.isEmpty())
    }

    @Test fun `full payload round-trips version and resolvers`() {
        val resp = HealthResponse(
            status = "ok",
            version = "1.0.0",
            resolvers = listOf("direct-url", "yt-dlp (opt-in enabled)"),
        )
        val encoded = json.encodeToString(HealthResponse.serializer(), resp)
        val decoded = json.decodeFromString(HealthResponse.serializer(), encoded)
        assertEquals("1.0.0", decoded.version)
        assertEquals(2, decoded.resolvers.size)
        assertEquals("yt-dlp (opt-in enabled)", decoded.resolvers[1])
    }

    @Test fun `unknown extra fields are ignored`() {
        val decoded = json.decodeFromString<HealthResponse>(
            """{"status":"ok","version":"2.0","resolvers":["x"],"uptimeSec":12345,"extra":{"nested":true}}"""
        )
        assertEquals("2.0", decoded.version)
        assertEquals(1, decoded.resolvers.size)
    }
}
