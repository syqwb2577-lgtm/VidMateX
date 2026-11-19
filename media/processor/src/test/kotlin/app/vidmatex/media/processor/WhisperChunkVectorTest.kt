package app.vidmatex.media.processor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact-token tests for the pure Whisper chunk vector (`WhisperChunkVector`). The vector
 * mirrors the production `splitIfNeeded` call without invoking FFmpegKit; native chunking
 * on device remains a device gap. Source-ready pending CI.
 */
class WhisperChunkVectorTest {

    @Test
    fun `chunk vector exact tokens and order`() {
        val args = WhisperChunkVector.build("/cache/src.mp4", "/cache/chunk_%03d.mp3", 600L)
        assertEquals(
            listOf(
                "-y", "-i", "/cache/src.mp4",
                "-vn",
                "-acodec", "libmp3lame", "-b:a", "64k",
                "-ar", "16000", "-ac", "1",
                "-f", "segment", "-segment_time", "600",
                "/cache/chunk_%03d.mp3",
            ),
            args,
        )
    }

    @Test
    fun `chunk constants match Whisper friendly format`() {
        assertEquals(16000, WhisperChunkVector.SAMPLE_RATE)
        assertEquals(1, WhisperChunkVector.CHANNELS)
        assertEquals("64k", WhisperChunkVector.BITRATE)
    }

    @Test
    fun `segment duration coerced to at least one second`() {
        val args = WhisperChunkVector.build("/s", "/c_%03d.mp3", 0L)
        assertEquals("1", args[args.indexOf("-segment_time") + 1])
    }

    @Test
    fun `vector never contains shell metacharacters as standalone tokens`() {
        val args = WhisperChunkVector.build("/tmp/v; rm -rf /.mp4", "/tmp/c_%03d.mp3", 600L)
        assertFalse(args.any { it == ";" || it == "&&" || it == "|" })
        assertTrue(args.contains("/tmp/v; rm -rf /.mp4"))
    }
}
