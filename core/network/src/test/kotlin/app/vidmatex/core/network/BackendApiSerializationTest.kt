package app.vidmatex.core.network

import app.vidmatex.core.model.MediaKind
import app.vidmatex.core.model.MediaTrack
import app.vidmatex.core.model.SourceType
import app.vidmatex.core.model.SubtitleTrack
import app.vidmatex.core.model.VideoMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serialization round-trip for backend API models, including the new
 * [ExtractResponse.downloadAvailable] and [ExtractResponse.message] fields.
 */
class BackendApiSerializationTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun sampleMetadata() = VideoMetadata(
        sourceType = SourceType.YOUTUBE,
        sourceUrl = "https://youtube.com/watch?v=1",
        sourceId = "v1",
        title = "Video",
    )

    @Test fun `ExtractResponse defaults to downloadAvailable true and null message`() {
        val resp = ExtractResponse(metadata = sampleMetadata())
        assertTrue(resp.downloadAvailable)
        assertNull(resp.message)
    }

    @Test fun `downloadAvailable false and message round-trip`() {
        val resp = ExtractResponse(
            metadata = sampleMetadata(),
            formats = listOf(MediaTrack("t1", MediaKind.VIDEO, "mp4")),
            subtitles = listOf(SubtitleTrack("s1", "en", "English", app.vidmatex.core.model.SubtitleKind.OFFICIAL)),
            streamUrls = mapOf("t1" to "https://cdn.example.com/v.mp4"),
            downloadAvailable = false,
            message = "Configure a compliant resolver to enable downloads.",
        )
        val encoded = json.encodeToString(ExtractResponse.serializer(), resp)
        val decoded = json.decodeFromString(ExtractResponse.serializer(), encoded)
        assertFalse(decoded.downloadAvailable)
        assertEquals("Configure a compliant resolver to enable downloads.", decoded.message)
        assertEquals(1, decoded.formats.size)
        assertEquals("https://cdn.example.com/v.mp4", decoded.streamUrls["t1"])
    }

    @Test fun `old backend JSON without new fields deserializes with defaults`() {
        val oldJson = """{
            "metadata":{"sourceType":"YOUTUBE","sourceUrl":"https://youtube.com/watch?v=1","sourceId":"v1","title":"V"},
            "formats":[],"subtitles":[],"streamUrls":{}
        }"""
        val decoded = json.decodeFromString(ExtractResponse.serializer(), oldJson)
        assertTrue(decoded.downloadAvailable)
        assertNull(decoded.message)
        assertEquals("v1", decoded.metadata.sourceId)
    }

    @Test fun `ExtractRequest and SubtitleRequest round-trip`() {
        val er = ExtractRequest("https://example.com")
        assertEquals(er.url, json.decodeFromString<ExtractRequest>(json.encodeToString(er)).url)
        val sr = SubtitleRequest("https://example.com", "track1", "srt")
        val decoded = json.decodeFromString<SubtitleRequest>(json.encodeToString(sr))
        assertEquals("track1", decoded.trackId)
        assertEquals("srt", decoded.format)
    }

    @Test fun `SubtitleRequest forFormat normalizes enum to lowercase backend contract`() {
        // Backend schemas.SubtitleRequest.format requires ^(srt|vtt|ass)$ (lowercase).
        // The Android SubtitleFormat enum is uppercase; forFormat must lowercase it.
        val srt = SubtitleRequest.forFormat("https://e.com", "t1", app.vidmatex.core.model.SubtitleFormat.SRT)
        assertEquals("srt", srt.format)
        val vtt = SubtitleRequest.forFormat("https://e.com", "t1", app.vidmatex.core.model.SubtitleFormat.VTT)
        assertEquals("vtt", vtt.format)
        val ass = SubtitleRequest.forFormat("https://e.com", "t1", app.vidmatex.core.model.SubtitleFormat.ASS)
        assertEquals("ass", ass.format)
        assertTrue(SubtitleRequest.ALLOWED_FORMATS.containsAll(setOf("srt", "vtt", "ass")))
    }

    @Test fun `error envelope shape matches backend contract`() {
        // Backend returns {"error":{"code":"...","message":"..."}}; the client parses
        // code+message via regex in ApiSafety. Verify the shape round-trips as JSON.
        val envelope = """{"error":{"code":"RATE_LIMITED","message":"Too many requests"}}"""
        val parsed = json.parseToJsonElement(envelope)
        val obj = parsed.jsonObject["error"]!!.jsonObject
        assertEquals("RATE_LIMITED", obj["code"]!!.jsonPrimitive.content)
        assertEquals("Too many requests", obj["message"]!!.jsonPrimitive.content)
    }

    @Test fun `LibreTranslate request omits api_key when null`() {
        val req = LibreTranslateRequest(q = "hi", source = "en", target = "ar")
        val encoded = json.encodeToString(LibreTranslateRequest.serializer(), req)
        assertFalse(encoded.contains("api_key"))
        val withKey = LibreTranslateRequest(q = "hi", source = "en", target = "ar", api_key = "k")
        val encodedKey = json.encodeToString(LibreTranslateRequest.serializer(), withKey)
        assertTrue(encodedKey.contains("\"api_key\":\"k\""))
    }
}
