package app.vidmatex.media.processor

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.model.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [WhisperVerboseJsonParser] — the production parser the real HTTP
 * client uses after requesting `response_format=verbose_json`. No Android, no OkHttp,
 * no network. Fixtures are inline JSON strings.
 *
 * Status: COMPLETE_NOT_RUN (no Android SDK/JDK 17 here; run via ./gradlew testDebugUnitTest).
 */
class WhisperVerboseJsonParserTest {
    @Test
    fun `parses verbose_json segments into timed cues preserving original timestamps`() {
        val json = """{
            "language": "en",
            "duration": 4.0,
            "segments": [
                {"id": 0, "start": 0.0, "end": 2.5, "text": "Hello world"},
                {"id": 1, "start": 3.0, "end": 4.0, "text": "Second cue"}
            ]
        }"""
        val result = WhisperVerboseJsonParser.parse(json)
        assertTrue(result is AppResult.Success)
        val cues = (result as AppResult.Success).value
        assertEquals(2, cues.size)
        assertEquals(0L, cues[0].startMs)
        assertEquals(2500L, cues[0].endMs)
        assertEquals(listOf("Hello world"), cues[0].lines)
        assertEquals(3000L, cues[1].startMs)
        assertEquals(4000L, cues[1].endMs)
        // Re-indexed 1..n.
        assertEquals(1, cues[0].index)
        assertEquals(2, cues[1].index)
    }

    @Test
    fun `arabic rtl text and punctuation preserved verbatim`() {
        val arabic = "مرحبا بالعالم، كيف حالك؟"
        val json = """{"segments":[{"id":0,"start":1.5,"end":3.25,"text":"$arabic"}]}"""
        val cues = (WhisperVerboseJsonParser.parse(json) as AppResult.Success).value
        assertEquals(1, cues.size)
        assertEquals(listOf(arabic), cues[0].lines)
        assertEquals(1500L, cues[0].startMs)
        assertEquals(3250L, cues[0].endMs)
    }

    @Test
    fun `multiline segment text splits into separate cue lines`() {
        val json = """{"segments":[{"id":0,"start":0.0,"end":2.0,"text":"Line one\nLine two"}]}"""
        val cues = (WhisperVerboseJsonParser.parse(json) as AppResult.Success).value
        assertEquals(listOf("Line one", "Line two"), cues[0].lines)
    }

    @Test
    fun `blank and empty segments are dropped`() {
        val json = """{"segments":[
            {"id":0,"start":0.0,"end":1.0,"text":"  "},
            {"id":1,"start":1.0,"end":2.0,"text":""},
            {"id":2,"start":2.0,"end":3.0,"text":"Real cue"}
        ]}"""
        val cues = (WhisperVerboseJsonParser.parse(json) as AppResult.Success).value
        assertEquals(1, cues.size)
        assertEquals(listOf("Real cue"), cues[0].lines)
    }

    @Test
    fun `empty response body is an error never a fake success`() {
        val result = WhisperVerboseJsonParser.parse("")
        assertTrue(result is AppResult.Error)
    }

    @Test
    fun `malformed json is an error`() {
        val result = WhisperVerboseJsonParser.parse("{not json")
        assertTrue(result is AppResult.Error)
    }

    @Test
    fun `zero recognizable cues is an error`() {
        val result = WhisperVerboseJsonParser.parse("""{"segments":[]}""")
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("no recognizable cues", ignoreCase = true))
    }

    @Test
    fun `unknown keys are ignored and missing segments treated as empty`() {
        val withExtra = """{"task":"transcribe","segments":[{"id":0,"start":0.0,"end":1.0,"text":"Hi","extra":true}]}"""
        val cues = (WhisperVerboseJsonParser.parse(withExtra) as AppResult.Success).value
        assertEquals(1, cues.size)
        val noSegments = """{"language":"en"}"""
        assertTrue(WhisperVerboseJsonParser.parse(noSegments) is AppResult.Error)
    }

    @Test
    fun `inverted end before start is clamped`() {
        val json = """{"segments":[{"id":0,"start":5.0,"end":2.0,"text":"Backwards"}]}"""
        val cues = (WhisperVerboseJsonParser.parse(json) as AppResult.Success).value
        assertEquals(5000L, cues[0].startMs)
        assertTrue(cues[0].endMs >= cues[0].startMs)
    }

    @Test
    fun `languageOf returns iso code or null`() {
        assertEquals("en", WhisperVerboseJsonParser.languageOf("""{"language":"English"}"""))
        assertEquals("ar", WhisperVerboseJsonParser.languageOf("""{"language":"ar"}"""))
        assertNull(WhisperVerboseJsonParser.languageOf("""{"segments":[]}"""))
        assertNull(WhisperVerboseJsonParser.languageOf("not json"))
    }

    @Test
    fun `cues sorted by start time and monotonic`() {
        val json = """{"segments":[
            {"id":9,"start":5.0,"end":6.0,"text":"third"},
            {"id":0,"start":0.0,"end":1.0,"text":"first"},
            {"id":4,"start":2.0,"end":3.0,"text":"second"}
        ]}"""
        val cues = (WhisperVerboseJsonParser.parse(json) as AppResult.Success).value
        assertEquals(listOf("first", "second", "third"), cues.map { it.lines[0] })
        assertEquals(listOf(1, 2, 3), cues.map { it.index })
        assertTrue(cues.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
    }
}
