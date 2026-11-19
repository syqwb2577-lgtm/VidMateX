package app.vidmatex.feature.subtitles.transcription

import app.vidmatex.core.model.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests for [TranscriptionPaths] (string path builder; no java.io.File or Android
 * calls in the tested logic).
 *
 * Written but NOT executed in this environment (no Android SDK / Gradle; host JDK 11
 * while the project targets JVM 17). Status: COMPLETE_NOT_RUN — pending CI.
 */
class TranscriptionPathsTest {

    @Test
    fun `outputDir namespaces files under transcriptions job id`() {
        assertEquals(
            "/data/user/0/app/files/transcriptions/job-123",
            TranscriptionPaths.outputDir("/data/user/0/app/files", "job-123"),
        )
    }

    @Test
    fun `outputDir sanitizes unsafe job id characters`() {
        assertEquals(
            "/data/files/transcriptions/ab_12",
            TranscriptionPaths.outputDir("/data/files/", "ab/12"),
        )
        assertEquals("/data/files/transcriptions/job", TranscriptionPaths.outputDir("/data/files", "///"))
    }

    @Test
    fun `srt and vtt paths use correct suffixes and fall back to default base name`() {
        assertEquals("/d/out/job.srt", TranscriptionPaths.srtFile("/d/out", "job"))
        assertEquals("/d/out/job.vtt", TranscriptionPaths.vttFile("/d/out", "job"))
        assertEquals("/d/out/transcription.srt", TranscriptionPaths.srtFile("/d/out", "   "))
        assertEquals("/d/out/transcription.vtt", TranscriptionPaths.vttFile("/d/out", ""))
    }

    @Test
    fun `suffix selection maps formats to subtitle extensions`() {
        assertEquals("srt", TranscriptionPaths.suffix(SubtitleFormat.SRT))
        assertEquals("vtt", TranscriptionPaths.suffix(SubtitleFormat.VTT))
        assertEquals("ass", TranscriptionPaths.suffix(SubtitleFormat.ASS))
    }

    @Test
    fun `input cache dir is transcription_in under cacheDir`() {
        assertEquals("/data/cache/transcription_in", TranscriptionPaths.inputCacheDir("/data/cache"))
    }

    @Test
    fun `output file names keep safe base and never contain path separators`() {
        assertEquals("/out/a_b_c.srt", TranscriptionPaths.srtFile("/out", "a/b c"))
        assertEquals("/out/transcription.vtt", TranscriptionPaths.vttFile("/out", "///"))
    }
}
