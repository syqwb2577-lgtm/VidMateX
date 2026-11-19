package app.vidmatex.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip serialization of DownloadSpec with the 6 new subtitle fields (CONTRACTS §1).
 * These fields must survive Json encode/decode so they can be passed into WorkManager
 * inputData and persisted into DownloadEntity.specJson.
 */
class DownloadSpecSerializationTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun sampleSpec() = DownloadSpec(
        metadata = VideoMetadata(
            sourceType = SourceType.DIRECT_URL,
            sourceUrl = "https://example.com/v.mp4",
            sourceId = "id1",
            title = "Test",
        ),
        mode = DownloadMode.VIDEO_AND_SUBTITLE,
        outputFileName = "test.mp4",
    )

    @Test fun `defaults preserve old behavior when new fields omitted`() {
        val spec = sampleSpec()
        assertNull(spec.subtitleContentOriginal)
        assertNull(spec.subtitleContentTranslated)
        assertFalse(spec.useTranslatedSubtitle)
        assertEquals(0L, spec.subtitleShiftMs)
        assertTrue(spec.saveOriginalSubtitleSidecar)
        assertNull(spec.translatedLanguageCode)
        // Additive E §1.2 flag: default false so old specs behave identically.
        assertFalse(spec.audioOnly)
    }

    @Test fun `new subtitle fields round-trip through serialization`() {
        val spec = sampleSpec().copy(
            subtitleContentOriginal = "1\n00:00:01,000 --> 00:00:02,000\nHi\n",
            subtitleContentTranslated = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا\n",
            useTranslatedSubtitle = true,
            subtitleShiftMs = 1500L,
            saveOriginalSubtitleSidecar = false,
            translatedLanguageCode = "ar",
        )
        val encoded = json.encodeToString(DownloadSpec.serializer(), spec)
        val decoded = json.decodeFromString(DownloadSpec.serializer(), encoded)
        assertEquals(spec.subtitleContentOriginal, decoded.subtitleContentOriginal)
        assertEquals(spec.subtitleContentTranslated, decoded.subtitleContentTranslated)
        assertTrue(decoded.useTranslatedSubtitle)
        assertEquals(1500L, decoded.subtitleShiftMs)
        assertFalse(decoded.saveOriginalSubtitleSidecar)
        assertEquals("ar", decoded.translatedLanguageCode)
    }

    @Test fun `old JSON without new fields deserializes with defaults`() {
        val oldJson = """{
            "metadata":{"sourceType":"DIRECT_URL","sourceUrl":"https://x/v","sourceId":"id","title":"t"},
            "mode":"VIDEO_AND_SUBTITLE",
            "outputFileName":"o.mp4"
        }"""
        val decoded = json.decodeFromString(DownloadSpec.serializer(), oldJson)
        assertNull(decoded.subtitleContentOriginal)
        assertFalse(decoded.useTranslatedSubtitle)
        assertEquals(0L, decoded.subtitleShiftMs)
        assertTrue(decoded.saveOriginalSubtitleSidecar)
        assertNull(decoded.translatedLanguageCode)
        // Old JSON predates audioOnly; default false must be applied (no unknown-key failure).
        assertFalse(decoded.audioOnly)
    }

    @Test fun `AppSettings resolverApiKey defaults to empty and round-trips`() {
        val default = AppSettings()
        assertEquals("", default.resolverApiKey)
        val withKey = default.copy(resolverApiKey = "secret-key")
        val encoded = json.encodeToString(AppSettings.serializer(), withKey)
        val decoded = json.decodeFromString(AppSettings.serializer(), encoded)
        assertEquals("secret-key", decoded.resolverApiKey)
    }

    // ---- E §1.1 / §1.2: additive AUDIO_ONLY mode + audioOnly flag ----

    @Test fun `explicit audioOnly=true serializes and round-trips with AUDIO_ONLY mode`() {
        val spec = sampleSpec().copy(mode = DownloadMode.AUDIO_ONLY, audioOnly = true)
        val encoded = json.encodeToString(DownloadSpec.serializer(), spec)
        assertTrue("encoded JSON must carry the explicit flag", encoded.contains("\"audioOnly\":true"))
        assertTrue("encoded JSON must carry the stable enum name", encoded.contains("\"mode\":\"AUDIO_ONLY\""))
        val decoded = json.decodeFromString(DownloadSpec.serializer(), encoded)
        assertTrue(decoded.audioOnly)
        assertEquals(DownloadMode.AUDIO_ONLY, decoded.mode)
    }

    @Test fun `AUDIO_ONLY enum name is stable and pre-existing values keep their names`() {
        // Enum persists as `name` in specJson; renaming/removing a value would break old rows.
        assertEquals("VIDEO_ONLY", DownloadMode.VIDEO_ONLY.name)
        assertEquals("SUBTITLE_ONLY", DownloadMode.SUBTITLE_ONLY.name)
        assertEquals("VIDEO_AND_SUBTITLE", DownloadMode.VIDEO_AND_SUBTITLE.name)
        assertEquals("AUDIO_ONLY", DownloadMode.AUDIO_ONLY.name)
        assertEquals(DownloadMode.AUDIO_ONLY, DownloadMode.valueOf("AUDIO_ONLY"))
    }

    @Test fun `structural audio-only equivalence holds without the explicit flag`() {
        // E compatibility rule: old/structural specs (videoTrack null, audioTrack set)
        // must decode with audioOnly=false and remain detectable by the worker structurally.
        val encoded = """{
            "metadata":{"sourceType":"DIRECT_URL","sourceUrl":"https://x/a.m4a","sourceId":"id","title":"audio"},
            "mode":"VIDEO_ONLY",
            "audioTrack":{"trackId":"a1","kind":"AUDIO","container":"m4a"},
            "outputFileName":"o.m4a"
        }"""
        val decoded = json.decodeFromString(DownloadSpec.serializer(), encoded)
        assertFalse(decoded.audioOnly)
        assertEquals(null, decoded.videoTrack)
        assertEquals("m4a", decoded.audioTrack?.container)
    }
}
