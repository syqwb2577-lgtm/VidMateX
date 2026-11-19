package app.vidmatex.feature.subtitles.transcription

import app.vidmatex.core.model.SubtitleFormat
import app.vidmatex.core.model.SubtitleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the standalone-subtitle import contract handled by
 * [TranscriptionFileImporter]'s validation layer ([SubtitleFileTypes]): extension
 * acceptance, format mapping, and HUMAN provenance. The Android content-URI copy/read
 * itself cannot run in this JVM environment and is covered on device.
 */
class TranscriptionFileImporterTest {

    @Test
    fun `supported extensions map to formats case-insensitively`() {
        assertEquals(SubtitleFormat.SRT, SubtitleFileTypes.formatForExtension("lecture.srt"))
        assertEquals(SubtitleFormat.VTT, SubtitleFileTypes.formatForExtension("captions.VTT"))
        assertEquals(SubtitleFormat.ASS, SubtitleFileTypes.formatForExtension("styles.ass"))
        assertTrue(SubtitleFileTypes.isSupportedSubtitleFileName("/sdcard/Download/movie.srt"))
    }

    @Test
    fun `unsupported and missing extensions rejected`() {
        assertNull(SubtitleFileTypes.formatForExtension("movie.mp4"))
        assertNull(SubtitleFileTypes.formatForExtension("audio.mp3"))
        assertNull(SubtitleFileTypes.formatForExtension("noextension"))
        assertNull(SubtitleFileTypes.formatForExtension(null))
        assertFalse(SubtitleFileTypes.isSupportedSubtitleFileName("clip.mkv"))
        assertFalse(SubtitleFileTypes.isSupportedSubtitleFileName("notes.txt"))
    }

    @Test
    fun `imported provenance is HUMAN and never automatic or official`() {
        assertEquals(SubtitleKind.HUMAN, SubtitleFileTypes.IMPORTED_PROVENANCE)
        assertTrue(SubtitleFileTypes.SUPPORTED_EXTENSIONS.containsAll(setOf("srt", "vtt", "ass")))
    }
}
