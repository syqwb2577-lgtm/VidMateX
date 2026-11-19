package app.vidmatex.media.processor

import app.vidmatex.core.model.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [CueTranslationMapper] (JVM-pure; no Android/ML Kit/network classes).
 * Contract: translated cues keep index/startMs/endMs and cue count; blank/missing results
 * fall back to original lines; a cue is never dropped or emptied. Pending CI execution.
 */
class CueTranslationMapperTest {

    private val cues = listOf(
        SubtitleCue(1, 1_000L, 3_000L, listOf("Hello world", "Second line")),
        SubtitleCue(2, 4_000L, 6_000L, listOf("Bonjour")),
        SubtitleCue(3, 7_000L, 8_000L, listOf("Keep me", "original line")),
    )

    @Test
    fun `applyPerCue replaces only lines and preserves index and timestamps`() {
        val translated = CueTranslationMapper.applyPerCue(
            cues,
            listOf(
                listOf("مرحبا بالعالم", "السطر الثاني"),
                listOf("Hola"),
                null, // missing translation -> fallback
            ),
        )
        assertEquals(3, translated.size)
        assertEquals(cues[0].index, translated[0].index)
        assertEquals(cues[0].startMs, translated[0].startMs)
        assertEquals(cues[0].endMs, translated[0].endMs)
        assertEquals(listOf("مرحبا بالعالم", "السطر الثاني"), translated[0].lines)
        assertEquals(listOf("Hola"), translated[1].lines)
        // Fallback: original lines and timestamps untouched.
        assertEquals(cues[2].lines, translated[2].lines)
        assertEquals(cues[2].startMs, translated[2].startMs)
    }

    @Test
    fun `blank-only translation entries fall back to original lines and never drop a cue`() {
        val translated = CueTranslationMapper.applyPerCue(
            cues,
            listOf(
                listOf("   ", ""), // whitespace only -> unusable
                null,
                listOf(""),
            ),
        )
        assertEquals(3, translated.size)
        assertEquals(cues[0].lines, translated[0].lines)
        assertEquals(cues[1].lines, translated[1].lines)
        assertEquals(cues[2].lines, translated[2].lines)
        translated.forEachIndexed { i, cue ->
            assertEquals(cues[i].index, cue.index)
            assertEquals(cues[i].startMs, cue.startMs)
            assertEquals(cues[i].endMs, cue.endMs)
            assertTrue(cue.lines.isNotEmpty())
        }
    }

    @Test
    fun `short translation list falls back for absent entries and keeps cue count`() {
        val translated = CueTranslationMapper.applyPerCue(
            cues,
            listOf(listOf("Only first translated")),
        )
        assertEquals(3, translated.size)
        assertEquals(listOf("Only first translated"), translated[0].lines)
        assertEquals(cues[1].lines, translated[1].lines)
        assertEquals(cues[2].lines, translated[2].lines)
    }

    @Test
    fun `splitToLines honors embedded newlines and merges excess lines`() {
        val two = CueTranslationMapper.splitToLines("line one\nline two\nline three", 2, listOf("fallback"))
        assertEquals(2, two.size)
        assertEquals("line one", two[0])
        assertEquals("line two line three", two[1])
    }

    @Test
    fun `splitToLines distributes words when no embedded newlines and target count greater than 1`() {
        val lines = CueTranslationMapper.splitToLines("one two three four five six", 3, listOf("fallback"))
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.isNotBlank() })
        assertEquals("one two three four five six", lines.joinToString(" "))
    }

    @Test
    fun `splitToLines blank raw falls back to original lines and target one returns single line`() {
        val fallback = listOf("original one", "original two")
        assertEquals(fallback, CueTranslationMapper.splitToLines("   ", 2, fallback))
        assertEquals(fallback, CueTranslationMapper.splitToLines("", 2, fallback))
        assertEquals(listOf("Single sentence"), CueTranslationMapper.splitToLines("  Single sentence  ", 1, fallback))
    }

    @Test
    fun `empty original lines survive fallback verbatim`() {
        val cueWithBlank = listOf(SubtitleCue(9, 10L, 20L, listOf("text", "")))
        val translated = CueTranslationMapper.applyPerCue(cueWithBlank, listOf(null))
        assertEquals(cueWithBlank[0].lines, translated[0].lines)
    }

    // ------------------------------------------------------- export / spec feeding
    @Test
    fun `buildExport applies delay to both ends keeps index count and language tag`() {
        val cues = listOf(
            SubtitleCue(1, 1_000L, 3_000L, listOf("Hello")),
            SubtitleCue(2, 4_000L, 6_000L, listOf("World")),
        )
        val export = CueTranslationMapper.buildExport(
            cues,
            listOf(listOf("مرحبا"), listOf("العالم")),
            shiftMs = 500L,
            languageCode = "AR",
        )
        assertEquals(2, export.cues.size)
        assertEquals(1, export.cues[0].index)
        assertEquals(1_500L, export.cues[0].startMs)
        assertEquals(3_500L, export.cues[0].endMs)
        assertEquals(4_500L, export.cues[1].startMs)
        assertEquals(6_500L, export.cues[1].endMs)
        assertEquals("ar", export.languageCode)
        assertEquals(500L, export.shiftMs)
        val fields = export.specFields()
        assertTrue(fields.useTranslatedSubtitle)
        assertEquals("ar", fields.translatedLanguageCode)
        assertEquals(500L, fields.subtitleShiftMs)
        assertEquals(2, fields.translatedCueCount)
    }

    @Test
    fun `buildExport negative shift clamps at zero and keeps end not before start`() {
        val cues = listOf(SubtitleCue(1, 200L, 800L, listOf("Hi")))
        val export = CueTranslationMapper.buildExport(cues, listOf(listOf("Hola")), -1_000L, "es")
        assertEquals(0L, export.cues[0].startMs)
        assertTrue(export.cues[0].endMs >= export.cues[0].startMs)
        assertEquals("es", export.languageCode)
    }

    @Test
    fun `buildExport fallback keeps Arabic punctuation verbatim and timing intact`() {
        val cues = listOf(SubtitleCue(1, 0L, 1_000L, listOf("مرحبا، كيف حالك؟ ؛ سطر")))
        val export = CueTranslationMapper.buildExport(cues, listOf(null), 250L, "ar")
        assertEquals("مرحبا، كيف حالك؟ ؛ سطر", export.cues[0].lines[0])
        assertEquals(250L, export.cues[0].startMs)
        assertEquals(1_250L, export.cues[0].endMs)
    }
}
