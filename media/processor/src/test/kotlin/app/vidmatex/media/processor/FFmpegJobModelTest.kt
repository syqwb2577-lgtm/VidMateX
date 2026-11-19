package app.vidmatex.media.processor

import app.vidmatex.core.model.EmbedStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the FFmpeg job model: [FFmpegJob], [FFmpegJobPlanner] vectors,
 * [StorageEstimate] pre-check math and [FFmpegError] mapping. No Android/FFmpegKit
 * classes are referenced; native execution remains a device gap (docs/FFMPEG.md §6).
 * Source-ready pending `./gradlew testDebugUnitTest`.
 */
class FFmpegJobModelTest {

    // ------------------------------------------------------------- job factories
    @Test
    fun `extract job carries operation inputs output and bitrate option`() {
        val job = FFmpegJob.extractMp3("/tmp/in.mp4", "/tmp/out.mp3", 192)
        assertEquals(FFmpegOperation.EXTRACT_AUDIO_MP3, job.operation)
        assertEquals("/tmp/in.mp4", job.primaryInput)
        assertEquals("/tmp/out.mp3", job.output)
        assertEquals(192, job.options.audioBitrateKbps)
    }

    @Test
    fun `soft embed job derives mov_text codec for mp4 and keeps language tag`() {
        val job = FFmpegJob.softEmbed("/v.mp4", "/s.srt", "/o.mp4", "ar", "mp4")
        assertEquals(FFmpegOperation.SOFT_EMBED_SUBTITLES, job.operation)
        assertEquals("mov_text", job.options.softSubtitleCodec)
        assertEquals("ar", job.options.languageTag)
    }

    @Test
    fun `soft embed job derives webvtt codec for webm`() {
        val job = FFmpegJob.softEmbed("/v.webm", "/s.vtt", "/o.webm", null, "webm")
        assertEquals("webvtt", job.options.softSubtitleCodec)
    }

    @Test
    fun `split job carries segment seconds pattern and operation`() {
        val job = FFmpegJob.splitSegments("/in.wav", "/tmp/chunk_%03d.mp3", 120L)
        assertEquals(FFmpegOperation.SPLIT_SEGMENTS, job.operation)
        assertEquals(120L, job.options.segmentSeconds)
    }

    // ------------------------------------------------------------- planner vectors
    @Test
    fun `extract vector exact token order`() {
        val job = FFmpegJob.extractMp3("/tmp/in.mp4", "/tmp/out.mp3")
        assertEquals(
            listOf(
                "-y", "-loglevel", "error",
                "-i", "/tmp/in.mp4",
                "-vn", "-c:a", "libmp3lame", "-b:a", "192k",
                "/tmp/out.mp3",
            ),
            FFmpegJobPlanner.vector(job),
        )
    }

    @Test
    fun `soft embed vector maps both streams copies and tags language`() {
        val job = FFmpegJob.softEmbed("/v.mp4", "/s.srt", "/o.mp4", "ar", "mp4")
        val v = FFmpegJobPlanner.vector(job)
        assertEquals("-i", v[3]); assertEquals("/v.mp4", v[4])
        assertEquals("-i", v[5]); assertEquals("/s.srt", v[6])
        assertTrue(v.containsAll(listOf("-map", "0", "-map", "1")))
        assertEquals("mov_text", v[v.indexOf("-c:s") + 1])
        assertTrue(v.contains("+faststart"))
        assertTrue(v.contains("language=ar"))
        assertEquals("/o.mp4", v.last())
    }

    @Test
    fun `hard burn vector carries subtitles filter x264 crf preset and audio copy`() {
        val style = EmbedStyle(fontSize = 24, fontName = "Sans")
        val job = FFmpegJob.hardBurn("/v.mp4", "/s.srt", "/o.mp4", style)
        val v = FFmpegJobPlanner.vector(job, style)
        val vf = v.indexOf("-vf")
        assertTrue(vf >= 0)
        val filter = v[vf + 1]
        assertTrue(filter.startsWith("subtitles='"))
        assertTrue(filter.contains("charenc=UTF-8"))
        assertEquals("libx264", v[v.indexOf("-c:v") + 1])
        assertEquals("18", v[v.indexOf("-crf") + 1])
        assertEquals("medium", v[v.indexOf("-preset") + 1])
        assertEquals("copy", v[v.indexOf("-c:a") + 1])
    }

    @Test
    fun `mux vector maps 0v 1a copies and shortest`() {
        val job = FFmpegJob.mux("/v.mp4", "/a.m4a", "/o.mp4")
        val v = FFmpegJobPlanner.vector(job)
        val firstMap = v.indexOf("-map")
        assertEquals("0:v", v[firstMap + 1])
        val secondMap = v.subList(firstMap + 1, v.size).indexOf("-map") + firstMap + 1
        assertEquals("1:a", v[secondMap + 1])
        assertTrue(v.contains("-shortest"))
    }

    @Test
    fun `remux vector stream copies into target muxer`() {
        val job = FFmpegJob.remux("/in.mp4", "/out.mkv", "mkv")
        val v = FFmpegJobPlanner.vector(job)
        assertEquals("copy", v[v.indexOf("-c") + 1])
        assertEquals("matroska", v[v.indexOf("-f") + 1])
    }

    @Test
    fun `split vector uses segment muxer with reset timestamps and pattern`() {
        val job = FFmpegJob.splitSegments("/in.mp4", "/tmp/chunk_%03d.mp4", 120L)
        val v = FFmpegJobPlanner.vector(job)
        assertEquals("segment", v[v.indexOf("-f") + 1])
        assertEquals("120", v[v.indexOf("-segment_time") + 1])
        assertEquals("1", v[v.indexOf("-reset_timestamps") + 1])
        assertEquals("/tmp/chunk_%03d.mp4", v.last())
    }

    // ------------------------------------------------------------- storage pre-check
    @Test
    fun `fits true when available covers estimate plus slack`() {
        assertTrue(StorageEstimate.fits(200L, 100L, 50L))
    }

    @Test
    fun `fits false when estimate plus slack exceeds available`() {
        assertFalse(StorageEstimate.fits(149L, 100L, 50L))
    }

    @Test
    fun `fits fails closed on negative or missing inputs`() {
        assertFalse(StorageEstimate.fits(-1L, 100L))
        assertFalse(StorageEstimate.fits(100L, -5L))
        assertFalse(StorageEstimate.fits(100L, 10L, -1L))
    }

    @Test
    fun `mp3Bytes equals seconds times bitrate over eight`() {
        // 1 s at 128 kbps = 16,000 bytes
        assertEquals(16_000L, StorageEstimate.mp3Bytes(1_000L, 128))
        assertEquals(0L, StorageEstimate.mp3Bytes(0L, 128))
    }

    @Test
    fun `copy and segment estimates mirror source bytes, burn uses conservative ceiling`() {
        assertEquals(1_500L, StorageEstimate.copyBytes(1_000L, 500L))
        assertEquals(1_000L, StorageEstimate.segmentBytes(1_000L))
        assertTrue(StorageEstimate.burnedBytes(1_000L) >= 1_000L)
    }

    @Test
    fun `estimate dispatches by operation`() {
        val sizes = mapOf("/v.mp4" to 1_000L)
        val extract = FFmpegJob.extractMp3("/v.mp4", "/o.mp3")
        assertTrue(StorageEstimate.estimate(extract, sizes) in 1L..1_000L)
        val remux = FFmpegJob.remux("/v.mp4", "/o.mkv", "mkv")
        assertEquals(1_000L, StorageEstimate.estimate(remux, sizes))
        val burn = FFmpegJob.hardBurn("/v.mp4", "/s.srt", "/o.mp4", EmbedStyle())
        assertTrue(StorageEstimate.estimate(burn, sizes) >= 1_000L)
        val split = FFmpegJob.splitSegments("/v.mp4", "/c_%03d.mp4", 60L)
        assertEquals(1_000L, StorageEstimate.estimate(split, sizes))
    }

    // ------------------------------------------------------------- typed errors
    @Test
    fun `non-zero exit keeps code and sanitized log and is recoverable`() {
        val err = FFmpegError.fromReturnCode(1, "Invalid stream") as FFmpegError.NonZeroExit
        assertEquals(1, err.exitCode)
        assertEquals("Invalid stream", err.lastLog)
        assertTrue(err.recoverable)
    }

    @Test
    fun `linkage error maps to NativeUnavailable and non-recoverable`() {
        val err = FFmpegError.fromThrowable(UnsatisfiedLinkError("libffmpegkit.so missing"))
        assertTrue(err is FFmpegError.NativeUnavailable)
        assertFalse(err.recoverable)
    }

    @Test
    fun `generic throwable maps to UnknownFailure`() {
        val err = FFmpegError.fromThrowable(RuntimeException("boom"))
        assertTrue(err is FFmpegError.UnknownFailure)
        assertFalse(err.recoverable)
    }

    @Test
    fun `insufficient storage carries required and available bytes`() {
        val err = FFmpegError.InsufficientStorage(2_000L, 500L)
        assertEquals(2_000L, err.requiredBytes)
        assertEquals(500L, err.availableBytes)
        assertTrue(err.recoverable)
    }
}
