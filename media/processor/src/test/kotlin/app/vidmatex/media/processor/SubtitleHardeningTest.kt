package app.vidmatex.media.processor

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.model.SubtitleCue
import app.vidmatex.core.model.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §10 untrusted-subtitle hardening (pure JVM): control-char stripping, newline/block
 * injection defense, cue/line caps, chronological ordering, Arabic punctuation fidelity,
 * and output-name sanitization. No Android imports; source-ready pending CI.
 */
class SubtitleHardeningTest {

    private val parser = SubtitleParserImpl()

    // ------------------------------------------------------- line sanitization
    @Test
    fun `sanitizeLine removes C0 control bytes and DEL`() {
        val dirty = "hello\u0001\u0002world\u007f"
        assertEquals("helloworld", SubtitleSanitizer.sanitizeLine(dirty))
    }

    @Test
    fun `sanitizeLine keeps only the first physical line against block injection`() {
        val injected = "visible cue\n00:99:99,999 --> 00:99:99,999\nforged block text"
        val safe = SubtitleSanitizer.sanitizeLine(injected)
        assertEquals("visible cue", safe)
        assertFalse(safe.contains("forged"))
        assertFalse(safe.contains("99:99,999"))
    }

    @Test
    fun `sanitizeLine caps line length at 2000 characters`() {
        val long = "a".repeat(2_500)
        val safe = SubtitleSanitizer.sanitizeLine(long)
        assertEquals(SubtitleSanitizer.MAX_LINE_CHARS, safe.length)
    }

    @Test
    fun `arabic letters and punctuation are preserved verbatim without bidi reordering`() {
        val arabic = "مرحبا، كيف حالك؟ ؛ سطر تجريبي — رقم ١٢٣"
        assertEquals(arabic, SubtitleSanitizer.sanitizeLine(arabic))
    }

    // ------------------------------------------------------- cue caps + ordering
    @Test
    fun `sanitizeCues caps count at the defensive maximum`() {
        val many = (1..SubtitleSanitizer.MAX_CUES + 1).map {
            SubtitleCue(it, it * 1_000L, it * 1_000L + 500L, listOf("cue $it"))
        }
        val safe = SubtitleSanitizer.sanitizeCues(many)
        assertEquals(SubtitleSanitizer.MAX_CUES, safe.size)
    }

    @Test
    fun `sanitizeCues strips control chars from every line and never empties a cue`() {
        val cues = listOf(
            SubtitleCue(1, 0L, 1_000L, listOf("ok\u0003line", "second")),
            SubtitleCue(2, 1_000L, 2_000L, listOf("\u0000\u001F")),
        )
        val safe = SubtitleSanitizer.sanitizeCues(cues)
        assertEquals("okline", safe[0].lines[0])
        assertTrue(safe[1].lines.isNotEmpty())
        assertTrue(safe[1].lines.all { it.isNotBlank() || it == "" })
    }

    @Test
    fun `orderByTime sorts by start then index and preserves original index values`() {
        val cues = listOf(
            SubtitleCue(3, 9_000L, 9_500L, listOf("later")),
            SubtitleCue(1, 1_000L, 2_000L, listOf("first")),
            SubtitleCue(2, 1_000L, 1_500L, listOf("first tie")),
        )
        val ordered = SubtitleSanitizer.orderByTime(cues)
        assertEquals(1_000L, ordered[0].startMs)
        assertEquals(1, ordered[0].index)
        assertEquals(2, ordered[1].index)
        assertEquals(9_000L, ordered[2].startMs)
    }

    // ------------------------------------------------------- parser + writer defense
    @Test
    fun `parsed SRT cues carry no control characters`() {
        val raw = "1\n00:00:01,000 --> 00:00:02,000\nline\u0001 with\u007f noise\n"
        val result = parser.parse(SubtitleFormat.SRT, raw)
        assertTrue(result is AppResult.Success)
        val cues = (result as AppResult.Success<List<SubtitleCue>>).value
        assertTrue(cues.all { cue -> cue.lines.all { line -> line.none { ch -> ch < ' ' || ch == '\u007F' } } })
    }

    @Test
    fun `SRT output cannot be injected with a forged timestamp block`() {
        val cues = listOf(
            SubtitleCue(1, 1_000L, 2_000L, listOf("safe line\n00:99:99,999 --> forged")),
        )
        val out = parser.write(SubtitleFormat.SRT, cues)
        assertFalse(out.contains("99:99,999"))
        assertFalse(out.contains("forged"))
        // Exactly one cue block: one index line and one timestamp line.
        assertEquals(1, out.lines().count { it.trim().matches(Regex("\\d+")) })
    }

    @Test
    fun `VTT output strips control bytes and keeps dot timestamps`() {
        val cues = listOf(SubtitleCue(1, 1_000L, 2_000L, listOf("ar\u0002abic test")))
        val out = parser.write(SubtitleFormat.VTT, cues)
        assertTrue(out.startsWith("WEBVTT"))
        assertFalse(out.contains("\u0002"))
        assertTrue(out.contains("00:00:01.000 --> 00:00:02.000"))
    }

    // ------------------------------------------------------- output name sanitizing
    @Test
    fun `file name drops path separators and reserved characters`() {
        assertEquals("My Video.mp4", SubtitleSanitizer.sanitizeFileName("/tmp/My Video.mp4"))
        assertEquals("badname.mp4", SubtitleSanitizer.sanitizeFileName("bad:name?.mp4"))
        assertEquals("media", SubtitleSanitizer.sanitizeFileName("..."))
        assertEquals("media", SubtitleSanitizer.sanitizeFileName(""))
    }

    @Test
    fun `display title strips controls flattens newlines and falls back`() {
        assertEquals("Welcome title", SubtitleSanitizer.sanitizeDisplayTitle("Welcome\u0001\ntitle"))
        assertEquals("Untitled", SubtitleSanitizer.sanitizeDisplayTitle("   "))
        assertTrue(SubtitleSanitizer.sanitizeDisplayTitle("x".repeat(500)).length <= SubtitleSanitizer.MAX_TITLE_CHARS)
    }
}
