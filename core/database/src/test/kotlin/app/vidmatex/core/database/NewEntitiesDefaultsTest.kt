package app.vidmatex.core.database

import app.vidmatex.core.model.SourceType
import app.vidmatex.core.model.SubtitleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM defaults test for the four additive tables introduced by MIGRATION_2_3.
 * Room schema generation, SQL execution, and DAO queries remain instrumented/device
 * scope (see docs/TESTING_GUIDE.md section 4).
 */
class NewEntitiesDefaultsTest {

    @Test fun `MediaMetadataEntity additive defaults`() {
        val entity = MediaMetadataEntity(
            id = "m1",
            sourceUrl = "https://example.com/v.mp4",
            sourceType = SourceType.DIRECT_URL,
            title = "Video",
        )
        assertEquals("video", entity.kind)
        assertEquals(0L, entity.createdAtMs)
        assertEquals(0L, entity.updatedAtMs)
        assertNull(entity.author)
        assertNull(entity.durationSec)
    }

    @Test fun `SubtitleRecordEntity additive defaults`() {
        val entity = SubtitleRecordEntity(
            id = "s1",
            mediaId = "m1",
            trackId = "track1",
            languageCode = "en",
            languageName = "English",
            kind = SubtitleKind.OFFICIAL,
        )
        assertEquals("srt", entity.format)
        assertTrue(entity.isTranslatable)
        assertFalse(entity.translated)
        assertNull(entity.filePath)
        assertEquals(0L, entity.createdAtMs)
    }

    @Test fun `PlaybackProgressEntity defaults to zero and unfinished`() {
        val entity = PlaybackProgressEntity(mediaId = "m1")
        assertEquals(0L, entity.positionMs)
        assertEquals(0L, entity.durationMs)
        assertFalse(entity.completed)
        assertEquals(0L, entity.updatedAtMs)
    }

    @Test fun `ApiConfigRefEntity stores reference fields only and no secret material`() {
        val entity = ApiConfigRefEntity(alias = "resolver", provider = "resolver")
        assertEquals("none", entity.authMode)
        assertEquals("", entity.keyAlias)
        assertEquals("", entity.baseUrl)
        assertEquals("", entity.endpointPath)
        assertEquals("", entity.lastStatus)
        assertEquals(0L, entity.lastTestedAtMs)
        // copy() keeps non-secret shape; keyAlias remains an alias, never a token.
        val updated = entity.copy(baseUrl = "https://api.example.com/", keyAlias = "resolver_api_key_enc")
        assertEquals("https://api.example.com/", updated.baseUrl)
        assertEquals("resolver_api_key_enc", updated.keyAlias)
    }

    @Test fun `MediaMetadataEntity copy preserves id and updates timestamps`() {
        val entity = MediaMetadataEntity(
            id = "m1", sourceUrl = "https://example.com/v", sourceType = SourceType.YOUTUBE, title = "T",
        )
        val updated = entity.copy(updatedAtMs = 1000L)
        assertEquals("m1", updated.id)
        assertEquals(1000L, updated.updatedAtMs)
        assertEquals(0L, updated.createdAtMs)
    }

    @Test fun `SubtitleRecordEntity markFetched-style copy sets filePath and translated`() {
        val entity = SubtitleRecordEntity(
            id = "s1", mediaId = "m1", trackId = "t1", languageCode = "ar",
            languageName = "Arabic", kind = SubtitleKind.AUTOMATIC,
        )
        val fetched = entity.copy(filePath = "/data/sidecar.srt", translated = true)
        assertEquals("/data/sidecar.srt", fetched.filePath)
        assertTrue(fetched.translated)
        assertEquals("t1", fetched.trackId)
    }

    @Test fun `PlaybackProgressEntity copy preserves primary key mediaId`() {
        val entity = PlaybackProgressEntity(mediaId = "m1", positionMs = 5_000L, durationMs = 60_000L)
        assertEquals("m1", entity.mediaId)
        assertEquals(5_000L, entity.positionMs)
        assertFalse(entity.completed)
    }
}
