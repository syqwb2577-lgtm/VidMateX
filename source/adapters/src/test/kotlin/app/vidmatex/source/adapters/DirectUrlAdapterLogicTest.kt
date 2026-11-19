package app.vidmatex.source.adapters

import app.vidmatex.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure logic extracted from [DirectUrlAdapter] (extension/container/kind
 * detection). The network HEAD call is not unit-tested here (requires a mock web
 * server); the resumable flag is derived from the Accept-Ranges header in listFormats.
 */
class DirectUrlAdapterLogicTest {

    @Test fun `extensionFromUrl strips query and lowercases`() {
        assertEquals("mp4", DirectUrlAdapter.extensionFromUrl("https://example.com/v.MP4?token=abc"))
        assertEquals("webm", DirectUrlAdapter.extensionFromUrl("https://example.com/a/b/c.webm"))
        assertEquals("mp3", DirectUrlAdapter.extensionFromUrl("https://example.com/song.mp3"))
    }

    @Test fun `containerFromExtension caps at 4 chars`() {
        assertEquals("mp4", DirectUrlAdapter.containerFromExtension("mp4"))
        assertEquals("webm", DirectUrlAdapter.containerFromExtension("webm"))
        assertEquals("mp4", DirectUrlAdapter.containerFromExtension("mkv123")) // >4 -> default
    }

    @Test fun `kindFromContent detects audio by content-type`() {
        assertEquals(MediaKind.AUDIO, DirectUrlAdapter.kindFromContent("audio/mpeg", "mp4"))
        assertEquals(MediaKind.AUDIO, DirectUrlAdapter.kindFromContent("audio/ogg", "mp4"))
        assertEquals(MediaKind.VIDEO, DirectUrlAdapter.kindFromContent("video/mp4", "mp4"))
        assertEquals(MediaKind.VIDEO, DirectUrlAdapter.kindFromContent("application/octet-stream", "mp4"))
    }

    @Test fun `kindFromContent detects audio by extension when content-type generic`() {
        assertEquals(MediaKind.AUDIO, DirectUrlAdapter.kindFromContent("application/octet-stream", "mp3"))
        assertEquals(MediaKind.AUDIO, DirectUrlAdapter.kindFromContent("", "m4a"))
        assertEquals(MediaKind.AUDIO, DirectUrlAdapter.kindFromContent("", "wav"))
        assertEquals(MediaKind.VIDEO, DirectUrlAdapter.kindFromContent("", "mp4"))
        assertEquals(MediaKind.VIDEO, DirectUrlAdapter.kindFromContent("", "webm"))
    }

    // ------------------------------------------------------- HTTP header parsing
    @Test fun `contentLengthBytes parses valid digits and rejects garbage or negatives`() {
        assertEquals(123_456L, DirectUrlAdapter.contentLengthBytes("123456"))
        assertEquals(0L, DirectUrlAdapter.contentLengthBytes("0"))
        assertNull(DirectUrlAdapter.contentLengthBytes(null))
        assertNull(DirectUrlAdapter.contentLengthBytes("abc"))
        assertNull(DirectUrlAdapter.contentLengthBytes("-5"))
    }

    @Test fun `resumableFromAcceptRanges recognizes bytes value only`() {
        assertTrue(DirectUrlAdapter.resumableFromAcceptRanges("bytes"))
        assertTrue(DirectUrlAdapter.resumableFromAcceptRanges("Bytes, id=..."))
        assertFalse(DirectUrlAdapter.resumableFromAcceptRanges("none"))
        assertFalse(DirectUrlAdapter.resumableFromAcceptRanges(null))
    }

    @Test fun `mimeBase strips parameters lowercases and rejects blanks`() {
        assertEquals("video/mp4", DirectUrlAdapter.mimeBase("video/mp4; codecs=\"avc1.42E01E\""))
        assertEquals("audio/mpeg", DirectUrlAdapter.mimeBase("AUDIO/MPEG"))
        assertNull(DirectUrlAdapter.mimeBase(""))
        assertNull(DirectUrlAdapter.mimeBase(null))
    }

    @Test fun `qualityCandidates yields one progressive track with length and resume flag`() {
        val tracks = DirectUrlAdapter.qualityCandidates("direct-0", "video/mp4", "mp4", 9_000L, true)
        assertEquals(1, tracks.size)
        val track = tracks[0]
        assertEquals("direct-0", track.trackId)
        assertEquals(9_000L, track.estimatedBytes)
        assertTrue(track.resumable)
        assertEquals("mp4", track.container)
        assertEquals(MediaKind.VIDEO, track.kind)
    }

    @Test fun `qualityCandidates marks audio by content type and non-resumable when header absent`() {
        val tracks = DirectUrlAdapter.qualityCandidates("direct-0", "audio/ogg", "ogg", null, false)
        assertEquals(MediaKind.AUDIO, tracks[0].kind)
        assertFalse(tracks[0].resumable)
        assertNull(tracks[0].estimatedBytes)
    }

    // ------------------------------------------------------- untrusted name sanitizing
    @Test fun `sanitizeFileName keeps basename and drops reserved characters`() {
        assertEquals("clip.mp4", SourceNameSanitizer.sanitizeFileName("/videos/clip.mp4"))
        assertEquals("clip.mp4", SourceNameSanitizer.sanitizeFileName("clip:?.mp4"))
        assertEquals("media", SourceNameSanitizer.sanitizeFileName("..."))
        assertEquals("media", SourceNameSanitizer.sanitizeFileName(""))
    }

    @Test fun `sanitizeDisplayTitle flattens controls and newlines with fallback`() {
        assertEquals("Welcome title", SourceNameSanitizer.sanitizeDisplayTitle("Welcome\u0001\ntitle"))
        assertEquals("Untitled", SourceNameSanitizer.sanitizeDisplayTitle("   "))
        assertTrue(SourceNameSanitizer.sanitizeDisplayTitle("x".repeat(500)).length <= 120)
    }
}
