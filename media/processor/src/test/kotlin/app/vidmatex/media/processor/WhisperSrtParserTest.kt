package app.vidmatex.media.processor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the internal [WhisperSrtParser] (pure, no network) and
 * [WhisperSpeechToText.buildTranscriptionUrl] URL normalization.
 */
class WhisperSrtParserTest {

    @Test fun `parse SRT extracts cues with timestamps and lines`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,500
            Hello world
            Second line

            2
            00:00:05,250 --> 00:00:09,000
            Another cue
        """.trimIndent()
        val cues = WhisperSrtParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(1, cues[0].index)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(4500L, cues[0].endMs)
        assertEquals(listOf("Hello world", "Second line"), cues[0].lines)
        assertEquals(2, cues[1].index)
        assertEquals(5250L, cues[1].startMs)
        assertEquals(9000L, cues[1].endMs)
    }

    @Test fun `parse tolerates VTT-style dot timestamps`() {
        val srt = "1\n00:00:01.500 --> 00:00:02.250\nDot timestamp\n"
        val cues = WhisperSrtParser.parse(srt)
        assertEquals(1, cues.size)
        assertEquals(1500L, cues[0].startMs)
        assertEquals(2250L, cues[0].endMs)
    }

    @Test fun `parse skips non-cue blocks and handles missing indices`() {
        val srt = """
            WEBVTT-like header line

            00:00:10,000 --> 00:00:12,000
            No index line here

            00:00:13,000 --> 00:00:15,000
            Also no index
        """.trimIndent()
        val cues = WhisperSrtParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(1, cues[0].index) // auto-assigned
        assertEquals(10000L, cues[0].startMs)
        assertEquals(2, cues[1].index)
    }

    @Test fun `parse empty or garbage returns empty list`() {
        assertTrue(WhisperSrtParser.parse("").isEmpty())
        assertTrue(WhisperSrtParser.parse("not a subtitle file at all").isEmpty())
    }

    @Test fun `buildTranscriptionUrl normalizes endpoint variants`() {
        assertEquals("https://api.example.com/v1/audio/transcriptions",
            WhisperSpeechToText.buildTranscriptionUrl("https://api.example.com"))
        assertEquals("https://api.example.com/v1/audio/transcriptions",
            WhisperSpeechToText.buildTranscriptionUrl("https://api.example.com/"))
        assertEquals("https://api.example.com/v1/audio/transcriptions",
            WhisperSpeechToText.buildTranscriptionUrl("https://api.example.com/v1"))
        assertEquals("https://api.example.com/v1/audio/transcriptions",
            WhisperSpeechToText.buildTranscriptionUrl("https://api.example.com/v1/audio/transcriptions"))
    }
}
