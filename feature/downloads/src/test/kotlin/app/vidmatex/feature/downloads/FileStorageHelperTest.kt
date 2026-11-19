package app.vidmatex.feature.downloads

import app.vidmatex.core.model.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for [FileStorageHelper] mime/extension/cleanup-policy helpers (no
 * Android needed). finalizeFile / cleanTempFiles / deleteStoredPath require a Context and
 * are exercised in instrumented tests only.
 *
 * Written but NOT executed (no Android SDK / JDK 17 in this environment).
 * Status: COMPLETE_NOT_RUN — pending CI (`./gradlew :feature:downloads:testDebugUnitTest`).
 */
class FileStorageHelperTest {
    @Test
    fun `subtitleExtension matches format`() {
        assertEquals("srt", FileStorageHelper.subtitleExtension(SubtitleFormat.SRT))
        assertEquals("vtt", FileStorageHelper.subtitleExtension(SubtitleFormat.VTT))
        assertEquals("ass", FileStorageHelper.subtitleExtension(SubtitleFormat.ASS))
    }
    @Test
    fun `subtitleMime matches format`() {
        assertEquals("application/x-subrip", FileStorageHelper.subtitleMime(SubtitleFormat.SRT))
        assertEquals("text/vtt", FileStorageHelper.subtitleMime(SubtitleFormat.VTT))
        assertEquals("text/plain", FileStorageHelper.subtitleMime(SubtitleFormat.ASS))
    }
    @Test
    fun `mediaMime maps known containers`() {
        assertEquals("video/mp4", FileStorageHelper.mediaMime("mp4"))
        assertEquals("video/mp4", FileStorageHelper.mediaMime("MP4")) // case-insensitive
        assertEquals("video/webm", FileStorageHelper.mediaMime("webm"))
        assertEquals("video/x-matroska", FileStorageHelper.mediaMime("mkv"))
        assertEquals("audio/mpeg", FileStorageHelper.mediaMime("mp3"))
        assertEquals("audio/mp4", FileStorageHelper.mediaMime("m4a"))
        assertEquals("audio/ogg", FileStorageHelper.mediaMime("ogg"))
        assertEquals("audio/wav", FileStorageHelper.mediaMime("wav"))
    }
    @Test
    fun `mediaMime falls back to octet stream for unknown`() {
        assertEquals("application/octet-stream", FileStorageHelper.mediaMime("xyz"))
    }

    // ------------------------------------------------------------ audio-only mime mapping (task 1)
    @Test
    fun `audioMime maps audio extensions`() {
        assertEquals("audio/mpeg", FileStorageHelper.audioMime("mp3"))
        assertEquals("audio/mp4", FileStorageHelper.audioMime("m4a"))
        assertEquals("audio/mp4", FileStorageHelper.audioMime("aac"))
        assertEquals("audio/ogg", FileStorageHelper.audioMime("ogg"))
        assertEquals("audio/ogg", FileStorageHelper.audioMime("opus"))
        assertEquals("audio/wav", FileStorageHelper.audioMime("wav"))
        assertEquals("audio/flac", FileStorageHelper.audioMime("flac"))
        assertEquals("audio/webm", FileStorageHelper.audioMime("webm"))
    }
    @Test
    fun `audioMime falls back to octet stream for unknown`() {
        assertEquals("application/octet-stream", FileStorageHelper.audioMime("xyz"))
    }

    // ------------------------------------------------------------ temp-file cleanup policy (task 6)
    @Test
    fun `cleanup policy deletes stream temps when partial not kept`() {
        val suffixes = FileStorageHelper.tempSuffixes(keepPartial = false)
        assertTrue(suffixes.contains("video"))
        assertTrue(suffixes.contains("audio"))
        assertTrue(suffixes.contains("mux"))
        assertTrue(suffixes.contains("sub"))
        assertTrue(suffixes.contains("embed"))
    }
    @Test
    fun `cleanup policy keeps resumable stream partials but deletes derived temps`() {
        val suffixes = FileStorageHelper.tempSuffixes(keepPartial = true)
        assertFalse(suffixes.contains("video"))
        assertFalse(suffixes.contains("audio"))
        assertTrue(suffixes.contains("mux"))
        assertTrue(suffixes.contains("sub"))
        assertTrue(suffixes.contains("embed"))
        assertTrue(suffixes.contains("sub.orig"))
    }

    // ------------------------------------------------------------ display-name sanitization (§7)
    @Test
    fun `final display name strips unsafe characters and keeps media extension`() {
        assertEquals("My_Video_2024.mp4", FileStorageHelper.sanitizeDisplayName("My Video (2024).mp4"))
        assertEquals("song.mp3", FileStorageHelper.sanitizeDisplayName("song?.mp3"))
        assertEquals("caption.srt", FileStorageHelper.sanitizeDisplayName("caption.srt"))
    }

    @Test
    fun `blocked executable extension on final name falls back to mp4`() {
        assertEquals("payload.mp4", FileStorageHelper.sanitizeDisplayName("payload.exe"))
        assertEquals("script.mp4", FileStorageHelper.sanitizeDisplayName("script.html"))
    }

    @Test
    fun `traversal in display name cannot produce a nested path`() {
        val safe = FileStorageHelper.sanitizeDisplayName("..\\..\\evil.sh")
        assertFalse(safe.contains(".."))
        assertFalse(safe.contains("/"))
        assertFalse(safe.contains("\\"))
    }
}
