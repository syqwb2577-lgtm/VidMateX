package app.vidmatex.feature.subtitles.transcription

import app.vidmatex.core.common.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [WhisperResponseParser] (verbose_json segment fixture) and
 * [CueOrdering]. Fixtures are inline JSON strings; no network, no Android.
 */
class WhisperResponseParserTest {

    @Test
    fun `parses verbose_json segments into timed ordered cues`() {
        val fixture = """
            {
              "task": "transcribe",
              "language": "english",
              "duration": 12.5,
              "segments": [
                {"id": 0, "start": 0.0, "end": 2.5, "text": "First cue\nsecond line"},
                {"id": 1, "start": 5.0, "end": 7.25, "text": "Middle cue"},
                {"id": 2, "start": 9.0, "end": 10.5, "text": "Last cue"}
              ]
            }
        """.trimIndent()
        val result = WhisperResponseParser.parseVerboseJson(fixture)
        assertTrue(result is AppResult.Success)
        val cues = (result as AppResult.Success).value
        assertEquals(3, cues.size)
        // Monotonic index + timeline order.
        assertEquals(1, cues[0].index)
        assertEquals(0L, cues[0].startMs)
        assertEquals(2500L, cues[0].endMs)
        assertEquals(listOf("First cue", "second line"), cues[0].lines)
        assertEquals(2, cues[1].index)
        assertEquals(5000L, cues[1].startMs)
        assertEquals(7250L, cues[1].endMs)
        assertEquals(3, cues[2].index)
        assertEquals(9000L, cues[2].startMs)
        assertEquals(10500L, cues[2].endMs)
    }

    @Test
    fun `out of order segments are sorted and reindexed monotonically`() {
        val fixture = """
            {
              "segments": [
                {"id": 5, "start": 20.0, "end": 21.0, "text": "Later"},
                {"id": 1, "start": 2.0, "end": 3.0, "text": "Early"}
              ]
            }
        """.trimIndent()
        val cues = (WhisperResponseParser.parseVerboseJson(fixture) as AppResult.Success).value
        assertEquals(2, cues.size)
        assertEquals("Early", cues[0].lines.first())
        assertEquals(1, cues[0].index)
        assertEquals("Later", cues[1].lines.first())
        assertEquals(2, cues[1].index)
        assertTrue(cues[1].startMs > cues[0].startMs)
    }

    @Test
    fun `blank segments are dropped and Arabic text preserved verbatim`() {
        val fixture = """
            {
              "language": "arabic",
              "segments": [
                {"id": 0, "start": 0.0, "end": 1.0, "text": "   "},
                {"id": 1, "start": 1.0, "end": 3.0, "text": "مرحبا بالعالم، كيف حالك؟"}
              ]
            }
        """.trimIndent()
        val cues = (WhisperResponseParser.parseVerboseJson(fixture) as AppResult.Success).value
        assertEquals(1, cues.size)
        // RTL string copied character-for-character; no logical reordering here.
        assertEquals("مرحبا بالعالم، كيف حالك؟", cues[0].lines.first())
        assertEquals("ar", WhisperResponseParser.languageOf(fixture))
    }

    @Test
    fun `malformed json and empty bodies fail with error, never fake cues`() {
        assertTrue(WhisperResponseParser.parseVerboseJson("{ not json").let { it is AppResult.Error })
        assertTrue(WhisperResponseParser.parseVerboseJson("").let { it is AppResult.Error })
        val noSegments = WhisperResponseParser.parseVerboseJson("""{"segments": []}""")
        assertTrue(noSegments is AppResult.Error)
        val onlyBlank = WhisperResponseParser.parseVerboseJson(
            """{"segments":[{"start":0,"end":1,"text":"  "}]}""",
        )
        assertTrue(onlyBlank is AppResult.Error)
        assertNull(WhisperResponseParser.languageOf("garbage"))
    }

    @Test
    fun `ensureMonotonic clamps inverted ranges and reindexes`() {
        val cues = CueOrdering.ensureMonotonic(
            listOf(
                app.vidmatex.core.model.SubtitleCue(3, 5000L, 4000L, listOf("x")),
                app.vidmatex.core.model.SubtitleCue(1, 1000L, 2000L, listOf("y")),
            ),
        )
        assertEquals(1000L, cues[0].startMs)
        assertEquals(2, cues[1].index)
        assertTrue(cues[1].endMs >= cues[1].startMs)
    }
}
