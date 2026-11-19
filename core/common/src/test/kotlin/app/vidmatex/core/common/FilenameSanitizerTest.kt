package app.vidmatex.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [FilenameSanitizer]: name sanitization, safe-name predicate,
 * and the relative-path traversal guard. No Android/IO calls under test.
 */
class FilenameSanitizerTest {

    @Test fun `unsafe punctuation and separators collapse to underscores`() {
        assertEquals("my_video_file", FilenameSanitizer.sanitizeFilename("my video:file?"))
        assertEquals("a_b_c", FilenameSanitizer.sanitizeFilename("a/b\\c"))
    }

    @Test fun `blank or all-unsafe names fall back to default`() {
        assertEquals("download", FilenameSanitizer.sanitizeFilename("   "))
        assertEquals("download", FilenameSanitizer.sanitizeFilename("///"))
    }

    @Test fun `traversal segments cannot survive into a single file name`() {
        val out = FilenameSanitizer.sanitizeFilename("../etc/passwd")
        assertFalse(out.contains("/"))
        assertFalse(out.contains("\\"))
        assertFalse(out.startsWith(".."))
    }

    @Test fun `unicode letters such as Arabic are preserved`() {
        assertEquals("فيديو", FilenameSanitizer.sanitizeFilename("فيديو"))
    }

    @Test fun `long names truncate to the 120 code point cap`() {
        val out = FilenameSanitizer.sanitizeFilename("x".repeat(200))
        assertEquals(120, out.length)
    }

    @Test fun `safe filename predicate accepts plain names and rejects traversal`() {
        assertTrue(FilenameSanitizer.isSafeFileName("video.mp4"))
        assertTrue(FilenameSanitizer.isSafeFileName("episode_1080p.mkv"))
        assertFalse(FilenameSanitizer.isSafeFileName("../x"))
        assertFalse(FilenameSanitizer.isSafeFileName("a/b.mp4"))
        assertFalse(FilenameSanitizer.isSafeFileName("trailing."))
        assertFalse(FilenameSanitizer.isSafeFileName(""))
    }

    @Test fun `relative path guard accepts clean segments and rejects absolutes drives and traversal`() {
        assertEquals("subs/en.srt", FilenameSanitizer.sanitizeRelativePath("subs/en.srt"))
        assertEquals("a/b/c.srt", FilenameSanitizer.sanitizeRelativePath("a/b/c.srt"))
        assertNull(FilenameSanitizer.sanitizeRelativePath("../etc/passwd"))
        assertNull(FilenameSanitizer.sanitizeRelativePath("/absolute/path.mp4"))
        assertNull(FilenameSanitizer.sanitizeRelativePath("C:\\win\\file.mp4"))
        assertNull(FilenameSanitizer.sanitizeRelativePath("a/b/../c"))
        assertNull(FilenameSanitizer.sanitizeRelativePath("bad:name.mp4"))
    }

    @Test fun `sanitizeFilename strips trailing dots and spaces`() {
        assertEquals("video", FilenameSanitizer.sanitizeFilename("video..."))
        assertEquals("video", FilenameSanitizer.sanitizeFilename("video   "))
        assertEquals("video", FilenameSanitizer.sanitizeFilename("video. . "))
    }

    @Test fun `sanitizeFilename with custom fallback uses sanitized fallback`() {
        assertEquals("media", FilenameSanitizer.sanitizeFilename("///", fallback = "media"))
        assertEquals("download", FilenameSanitizer.sanitizeFilename("   ", fallback = "   "))
    }

    @Test fun `isSafeFileName rejects over-long and control chars`() {
        assertFalse(FilenameSanitizer.isSafeFileName("x".repeat(121)))
        assertFalse(FilenameSanitizer.isSafeFileName("video\u0000.mp4"))
        assertTrue(FilenameSanitizer.isSafeFileName("x".repeat(120)))
    }

    @Test fun `relative path guard rejects empty and over-segmented`() {
        assertNull(FilenameSanitizer.sanitizeRelativePath(""))
        assertNull(FilenameSanitizer.sanitizeRelativePath("a/b/c/d/e/f/g/h/i/j", maxSegments = 8))
        // Collapsed separators are filtered and accepted (a//b -> ["a","b"]).
        assertEquals("a/b", FilenameSanitizer.sanitizeRelativePath("a//b"))
    }

    @Test fun `dot segments alone are rejected in relative path`() {
        assertNull(FilenameSanitizer.sanitizeRelativePath("."))
        assertNull(FilenameSanitizer.sanitizeRelativePath(".."))
        assertNull(FilenameSanitizer.sanitizeRelativePath("a/./b"))
    }
}
