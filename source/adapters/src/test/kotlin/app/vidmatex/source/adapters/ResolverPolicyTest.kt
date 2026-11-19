package app.vidmatex.source.adapters

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.model.MediaTrack
import app.vidmatex.core.model.SourceType
import app.vidmatex.core.model.SubtitleFormat
import app.vidmatex.core.model.SubtitleTrack
import app.vidmatex.core.model.VideoMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for platform compliance posture, resolver error-state mapping and adapter
 * selection (JVM-only; no Retrofit/Android calls). Network execution and backend payloads
 * remain device/backend gaps.
 */
class ResolverPolicyTest {

    @Test
    fun `platform adapters are metadata-only- no device download, no scraping`() {
        assertFalse(SourceCompliance.PLATFORM_DOWNLOAD_AVAILABLE)
        assertFalse(SourceCompliance.SCRAPING_ALLOWED)
        SourceType.values().forEach { type ->
            assertTrue("compliance note missing for $type", SourceCompliance.note(type).isNotBlank())
        }
    }

    @Test
    fun `http status maps to typed resolver states with recoverability flags`() {
        assertTrue(ResolverState.http(200) is ResolverState.Ok)
        assertTrue(ResolverState.http(401) is ResolverState.Unauthorized)
        assertTrue(ResolverState.http(403) is ResolverState.Unauthorized)
        assertTrue(ResolverState.http(408) is ResolverState.RateLimited)
        assertTrue(ResolverState.http(429) is ResolverState.RateLimited)
        assertTrue(ResolverState.http(500) is ResolverState.ServerError)
        assertTrue(ResolverState.http(503) is ResolverState.ServerError)
        assertTrue(ResolverState.http(400) is ResolverState.InvalidUrl)
        assertTrue(ResolverState.http(429).recoverable)
        assertTrue(ResolverState.http(401).recoverable)
        assertFalse(ResolverState.http(503).recoverable)
    }

    @Test
    fun `throwable class names map to timeout no-network or unknown states`() {
        assertTrue(ResolverState.throwableName("SocketTimeoutException") is ResolverState.Timeout)
        assertTrue(ResolverState.throwableName("UnknownHostException") is ResolverState.NoNetwork)
        assertTrue(ResolverState.throwableName("ConnectException") is ResolverState.NoNetwork)
        assertTrue(ResolverState.throwableName("BizarreError") is ResolverState.Unknown)
        assertTrue(ResolverState.throwableName(null) is ResolverState.Unknown)
    }

    @Test
    fun `selection picks the matching platform adapter and rejects unknown hosts`() {
        val adapters = listOf(
            FakeAdapter(SourceType.YOUTUBE, listOf("youtube.com", "youtu.be")),
            FakeAdapter(SourceType.FACEBOOK, listOf("facebook.com", "fb.watch")),
            FakeAdapter(SourceType.TIKTOK, listOf("tiktok.com")),
            FakeAdapter(SourceType.DIRECT_URL, listOf("example.com")),
        )
        assertEquals(
            SourceType.YOUTUBE,
            (AdapterSelection.select("https://www.youtube.com/watch?v=x", adapters) as AppResult.Success).value.sourceType,
        )
        assertEquals(
            SourceType.TIKTOK,
            (AdapterSelection.select("https://www.tiktok.com/@user/video", adapters) as AppResult.Success).value.sourceType,
        )
        assertEquals(
            SourceType.FACEBOOK,
            (AdapterSelection.select("https://fb.watch/abc123/", adapters) as AppResult.Success).value.sourceType,
        )
        assertEquals(
            SourceType.DIRECT_URL,
            (AdapterSelection.select("https://example.com/video.mp4", adapters) as AppResult.Success).value.sourceType,
        )
        assertTrue(AdapterSelection.select("https://unknown.example.org/", adapters) is AppResult.Error)
        assertTrue(AdapterSelection.select("", adapters) is AppResult.Error)
    }

    /** Minimal in-test adapter; stands in for the Hilt-provided adapters without network. */
    private class FakeAdapter(
        override val sourceType: SourceType,
        private val hosts: List<String>,
    ) : SourceAdapter {
        override fun matches(url: String): Boolean {
            val host = runCatching { java.net.URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
            return hosts.any { host.contains(it) }
        }
        override suspend fun resolve(url: String): AppResult<VideoMetadata> = AppResult.Error("fake")
        override suspend fun listFormats(metadata: VideoMetadata): AppResult<List<MediaTrack>> =
            AppResult.Success(emptyList())
        override suspend fun listSubtitles(metadata: VideoMetadata): AppResult<List<SubtitleTrack>> =
            AppResult.Success(emptyList())
        override suspend fun fetchSubtitle(
            metadata: VideoMetadata,
            track: SubtitleTrack,
            format: SubtitleFormat,
        ): AppResult<String> = AppResult.Error("fake")
        override suspend fun streamUrl(metadata: VideoMetadata, track: MediaTrack): AppResult<String> =
            AppResult.Error("fake")
    }
}
