package app.vidmatex.feature.downloads

import app.vidmatex.core.model.DownloadMode
import app.vidmatex.core.model.DownloadSpec
import app.vidmatex.core.model.SourceType
import app.vidmatex.core.model.VideoMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [DownloadInputValidator] (security/scheme/SSRF/compliance policy).
 * No Android imports; `UrlValidator.isLikelyPrivateHost`/`canonicalize` used here are pure
 * JVM functions. Status: source-ready pending CI JDK 17 run.
 */
class DownloadInputValidatorTest {

    private fun spec(
        url: String,
        type: SourceType,
        mode: DownloadMode = DownloadMode.VIDEO_ONLY,
        subtitleContent: String? = null,
    ): DownloadSpec {
        val metadata = VideoMetadata(
            sourceType = type,
            sourceUrl = url,
            sourceId = "id",
            title = "Title",
        )
        return DownloadSpec(
            metadata = metadata,
            mode = mode,
            outputFileName = "title",
            subtitleContentOriginal = subtitleContent,
        )
    }

    // ---- happy path ----
    @Test
    fun `valid https direct url passes and canonicalizes`() {
        val result = DownloadInputValidator.validateDirectUrl(
            "https://example.com/media/video.mp4", SourceType.DIRECT_URL,
        )
        assertTrue(result.ok)
        assertEquals("https://example.com/media/video.mp4", result.canonicalUrl)
    }

    // ---- scheme rules ----
    @Test
    fun `plain http direct url rejected as non https`() {
        val result = DownloadInputValidator.validateDirectUrl(
            "http://example.com/video.mp4", SourceType.DIRECT_URL,
        )
        assertEquals(DownloadInputValidator.Reason.NON_HTTPS, result.reason)
        assertFalse(result.ok)
    }

    @Test
    fun `blocked schemes rejected before parsing`() {
        listOf(
            "javascript:alert(1)",
            "file:///sdcard/video.mp4",
            "data:video/mp4;base64,AAAA",
            "ftp://example.com/v.mp4",
            "content://media/external/video.mp4",
            "ws://example.com/socket",
        ).forEach { url ->
            val r = DownloadInputValidator.validateDirectUrl(url, SourceType.DIRECT_URL)
            assertEquals("$url should be blocked", DownloadInputValidator.Reason.BLOCKED_SCHEME, r.reason)
        }
    }

    @Test
    fun `malformed and hostless urls rejected`() {
        assertEquals(
            DownloadInputValidator.Reason.MALFORMED,
            DownloadInputValidator.validateDirectUrl("not a url!!", SourceType.DIRECT_URL).reason,
        )
        assertEquals(
            DownloadInputValidator.Reason.NO_HOST,
            DownloadInputValidator.validateDirectUrl("https:///path", SourceType.DIRECT_URL).reason,
        )
        assertEquals(
            DownloadInputValidator.Reason.BLANK,
            DownloadInputValidator.validateDirectUrl("   ", SourceType.DIRECT_URL).reason,
        )
    }

    @Test
    fun `oversized url rejected`() {
        val long = "https://example.com/" + "a".repeat(2100)
        assertEquals(
            DownloadInputValidator.Reason.TOO_LONG,
            DownloadInputValidator.validateDirectUrl(long, SourceType.DIRECT_URL).reason,
        )
    }

    // ---- SSRF / private host rules ----
    @Test
    fun `loopback private and reserved hosts rejected`() {
        val privateHosts = listOf(
            "https://127.0.0.1/v.mp4",
            "https://10.0.0.5/v.mp4",
            "https://192.168.1.20/v.mp4",
            "https://172.16.4.9/v.mp4",
            "https://169.254.10.1/v.mp4",
            "https://localhost/v.mp4",
            "https://nas.local/v.mp4",
            "https://[::1]/v.mp4",
            "https://[fd00::1]/v.mp4",
        )
        privateHosts.forEach { url ->
            val r = DownloadInputValidator.validateDirectUrl(url, SourceType.DIRECT_URL)
            assertEquals("$url should be private", DownloadInputValidator.Reason.PRIVATE_HOST, r.reason)
        }
    }

    @Test
    fun `public https host accepted`() {
        val r = DownloadInputValidator.validateDirectUrl(
            "https://cdn.stream.example.org/files/v.mp4", SourceType.DIRECT_URL,
        )
        assertTrue(r.ok)
    }

    // ---- compliance: platform pages metadata-only ----
    @Test
    fun `platform source types rejected as metadata only`() {
        listOf(SourceType.YOUTUBE, SourceType.FACEBOOK, SourceType.TIKTOK).forEach { type ->
            val r = DownloadInputValidator.validateDirectUrl("https://youtube.com/watch?v=x", type)
            assertEquals(DownloadInputValidator.Reason.PLATFORM_METADATA_ONLY, r.reason)
            assertFalse(r.recoverable)
        }
    }

    @Test
    fun `unknown source type rejected unsupported`() {
        val r = DownloadInputValidator.validateDirectUrl("https://example.com/", SourceType.UNKNOWN)
        assertEquals(DownloadInputValidator.Reason.UNSUPPORTED_SOURCE, r.reason)
    }

    // ---- spec-level gating ----
    @Test
    fun `media spec validates its url`() {
        val bad = DownloadInputValidator.validate(spec("http://example.com/v.mp4", SourceType.DIRECT_URL))
        assertEquals(DownloadInputValidator.Reason.NON_HTTPS, bad.reason)
        val good = DownloadInputValidator.validate(spec("https://example.com/v.mp4", SourceType.DIRECT_URL))
        assertTrue(good.ok)
    }

    @Test
    fun `subtitle-only spec with serialized content bypasses network validation`() {
        val local = DownloadInputValidator.validate(
            spec("local-transcript", SourceType.UNKNOWN, DownloadMode.SUBTITLE_ONLY, subtitleContent = "1\n00:00:01 --> x"),
        )
        assertTrue(local.ok)
    }

    @Test
    fun `subtitle-only spec without content validates network url`() {
        val bad = DownloadInputValidator.validate(
            spec("ftp://example.com/s.srt", SourceType.DIRECT_URL, DownloadMode.SUBTITLE_ONLY),
        )
        assertEquals(DownloadInputValidator.Reason.BLOCKED_SCHEME, bad.reason)
    }

    @Test
    fun `stream url validator applies direct url rules`() {
        assertTrue(DownloadInputValidator.validateStreamUrl("https://cdn.example.com/a.m4a").ok)
        assertEquals(
            DownloadInputValidator.Reason.PRIVATE_HOST,
            DownloadInputValidator.validateStreamUrl("https://192.168.0.2/a.m4a").reason,
        )
        assertEquals(
            "https://cdn.example.com/a.m4a",
            DownloadInputValidator.validateStreamUrl("https://cdn.example.com/a.m4a").canonicalUrl,
        )
    }
}
