package app.vidmatex.feature.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [FileNamePolicy] (requirement §7 sanitization, traversal and
 * executable-extension defense). No Android imports. Source-ready pending CI.
 */
class FileNamePolicyTest {

    @Test
    fun `unsafe separators and punctuation collapse to underscore`() {
        assertEquals("my_video_file", FileNamePolicy.sanitize("my video:file?"))
        assertEquals("a_b_c", FileNamePolicy.sanitize("a/b\\c"))
    }

    @Test
    fun `blank or separator-only names fall back`() {
        assertEquals("download", FileNamePolicy.sanitize("   "))
        assertEquals("download", FileNamePolicy.sanitize("///"))
        assertEquals("custom", FileNamePolicy.sanitize("   ", fallback = "custom"))
    }

    @Test
    fun `control characters are removed`() {
        val withNul = "abc\u0000def"
        assertEquals("abcdef", FileNamePolicy.sanitize(withNul))
        val withCrLf = "line1\r\nline2"
        assertEquals("line1_line2", FileNamePolicy.sanitize(withCrLf))
    }

    @Test
    fun `traversal dot segments are neutralized`() {
        val sanitized = FileNamePolicy.sanitize("../../etc/passwd")
        assertFalse(sanitized.contains(".."))
        assertFalse(sanitized.startsWith("/"))
        assertFalse(sanitized.startsWith("\\"))
    }

    @Test
    fun `name capped at 120 chars`() {
        assertEquals(120, FileNamePolicy.sanitize("z".repeat(250)).length)
    }

    @Test
    fun `media extensions allowed, executable and web extensions blocked`() {
        assertTrue(FileNamePolicy.extensionAllowed("mp4"))
        assertTrue(FileNamePolicy.extensionAllowed(".MP3"))
        assertTrue(FileNamePolicy.extensionAllowed("srt"))
        assertFalse(FileNamePolicy.extensionAllowed("exe"))
        assertFalse(FileNamePolicy.extensionAllowed("html"))
        assertFalse(FileNamePolicy.extensionAllowed("js"))
        assertFalse(FileNamePolicy.extensionAllowed("apk"))
        assertTrue(FileNamePolicy.dangerousExtension("sh"))
        assertTrue(FileNamePolicy.dangerousExtension(".SVG"))
        assertFalse(FileNamePolicy.dangerousExtension("mkv"))
    }

    @Test
    fun `safeDisplayName rejects blocked extension and falls back to mp4`() {
        assertEquals("movie.mp4", FileNamePolicy.safeDisplayName("movie", "exe"))
        assertEquals("song.mp3", FileNamePolicy.safeDisplayName("song", "mp3"))
        assertEquals("caption.srt", FileNamePolicy.safeDisplayName("caption", "srt"))
    }
}
