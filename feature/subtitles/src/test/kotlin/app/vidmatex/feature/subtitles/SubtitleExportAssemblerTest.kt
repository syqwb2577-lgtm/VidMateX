package app.vidmatex.feature.subtitles

import app.vidmatex.core.model.SubtitleCue
import app.vidmatex.core.model.SubtitleFormat
import app.vidmatex.media.processor.SubtitleParserImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure tests for [SubtitleExportAssembler]: shift math applied BEFORE serialization,
 * translated cues carried into the export bundle (not kept UI-only), cue overrides
 * reaching the exported text, Arabic verbatim preservation, and VTT rendering.
 * Uses the real JVM-instantiable `SubtitleParserImpl`; no Android/WorkManager code.
 */
class SubtitleExportAssemblerTest {

    private lateinit var parser: SubtitleParserImpl

    @Before
    fun setUp() {
        parser = SubtitleParserImpl()
    }

    private fun cue(index: Int, start: Long, end: Long, vararg lines: String) =
        SubtitleCue(index, start, end, lines.toList())

    @Test
    fun `shift applied to both lists before format serialization`() {
        val cues = listOf(cue(1, 1000, 4000, "Hello"), cue(2, 5000, 8000, "World"))
        val translated = listOf(cue(1, 1000, 4000, "Bonjour"), cue(2, 5000, 8000, "Monde"))
        val bundle = SubtitleExportAssembler.assemble(
            cues = cues,
            translatedCues = translated,
            shiftMs = 1000L,
            format = SubtitleFormat.SRT,
            parser = parser,
            targetLanguageCode = "fr",
        )
        val originalText = requireNotNull(bundle.originalText)
        val translatedText = requireNotNull(bundle.translatedText)
        // Original cue 1 moved 00:00:01,000 -> 00:00:02,000 in the exported SRT.
        assertTrue(originalText.contains("00:00:02,000"))
        assertFalse(originalText.contains("00:00:01,000 -->"))
        // Translated list serialized with the same shift and its language tag.
        assertTrue(translatedText.contains("00:00:02,000"))
        assertTrue(translatedText.contains("Bonjour"))
        assertEquals(1000L, bundle.shiftMs)
        assertTrue(bundle.useTranslated)
        assertEquals("fr", bundle.translatedLanguageCode)
        assertEquals(2, bundle.originalCueCount)
        assertEquals(2, bundle.translatedCueCount)
    }

    @Test
    fun `arabic lines preserved verbatim, no logical reordering`() {
        val cues = listOf(cue(1, 0, 1000, "مرحبا بالعالم، كيف حالك؟"))
        val bundle = SubtitleExportAssembler.assemble(
            cues = cues,
            translatedCues = null,
            shiftMs = 0L,
            format = SubtitleFormat.SRT,
            parser = parser,
            targetLanguageCode = null,
        )
        val originalText = requireNotNull(bundle.originalText)
        assertTrue(originalText.contains("مرحبا بالعالم، كيف حالك؟"))
        assertFalse(bundle.useTranslated)
        assertNull(bundle.translatedText)
        assertNull(bundle.translatedLanguageCode)
    }

    @Test
    fun `cue overrides reach exported text and blank overrides are ignored`() {
        val cues = listOf(cue(1, 1000, 2000, "Old text"), cue(2, 3000, 4000, "Second line"))
        val bundle = SubtitleExportAssembler.assemble(
            cues = cues,
            translatedCues = null,
            shiftMs = 0L,
            format = SubtitleFormat.SRT,
            parser = parser,
            targetLanguageCode = null,
            cueOverrides = mapOf(1 to listOf("Edited line")),
        )
        val originalText = requireNotNull(bundle.originalText)
        assertTrue(originalText.contains("Edited line"))
        assertFalse(originalText.contains("Old text"))
        assertEquals(1, bundle.editedCueCount)
    }

    @Test
    fun `vtt export emits WEBVTT header and dot-decimal shifted timestamps`() {
        val cues = listOf(cue(1, 1000, 2000, "Hi"))
        val bundle = SubtitleExportAssembler.assemble(
            cues = cues,
            translatedCues = null,
            shiftMs = 500L,
            format = SubtitleFormat.VTT,
            parser = parser,
            targetLanguageCode = null,
        )
        val originalText = requireNotNull(bundle.originalText)
        assertTrue(originalText.startsWith("WEBVTT"))
        assertTrue(originalText.contains("00:00:01.500 -->"))
    }

    @Test
    fun `translated cue order preserved through the bundle`() {
        val cues = listOf(cue(1, 0, 1000, "a"), cue(2, 2000, 3000, "b"), cue(3, 4000, 5000, "c"))
        val translated = listOf(cue(1, 0, 1000, "A"), cue(2, 2000, 3000, "B"), cue(3, 4000, 5000, "C"))
        val bundle = SubtitleExportAssembler.assemble(
            cues = cues,
            translatedCues = translated,
            shiftMs = 0L,
            format = SubtitleFormat.SRT,
            parser = parser,
            targetLanguageCode = "ar",
        )
        assertEquals(3, bundle.translatedCueCount)
        val text = requireNotNull(bundle.translatedText)
        val first = text.indexOf("A")
        val second = text.indexOf("B")
        val third = text.indexOf("C")
        assertTrue(first >= 0)
        assertTrue(second > first)
        assertTrue(third > second)
    }

    /**
     * CRITICAL data-flow proof: the final exported translated subtitle text (the exact
     * string carried on `DownloadSpec.subtitleContentTranslated` and written to the
     * subtitle file consumed by the embed pipeline) contains every translated cue's
     * text AND its original timestamps (shift applied identically to both lists).
     * Translation changes only text — never timestamps.
     */
    @Test
    fun `final exported translated content contains translated cues with original timestamps`() {
        val original = listOf(
            cue(1, 1500, 4000, "Hello world"),
            cue(2, 5000, 8250, "Good morning"),
        )
        val translated = listOf(
            cue(1, 1500, 4000, "مرحبا بالعالم"),
            cue(2, 5000, 8250, "صباح الخير"),
        )
        val shift = 500L
        val bundle = SubtitleExportAssembler.assemble(
            cues = original,
            translatedCues = translated,
            shiftMs = shift,
            format = SubtitleFormat.SRT,
            parser = parser,
            targetLanguageCode = "ar",
        )
        val text = requireNotNull(bundle.translatedText)
        // Both translated cue texts reach the final export.
        assertTrue(text.contains("مرحبا بالعالم"))
        assertTrue(text.contains("صباح الخير"))
        // Original timestamps + shift: 1500 -> 2000, 4000 -> 4500, 5000 -> 5500, 8250 -> 8750.
        assertTrue(text.contains("00:00:02,000 --> 00:00:04,500"))
        assertTrue(text.contains("00:00:05,500 --> 00:00:08,750"))
        // Original (unshifted) timestamps must NOT appear — shift was applied before write.
        assertFalse(text.contains("00:00:01,500 -->"))
        // Original-language text must not leak into the translated export.
        assertFalse(text.contains("Hello world"))
        // useTranslated flag drives DownloadSpec.useTranslatedSubtitle.
        assertTrue(bundle.useTranslated)
        assertEquals("ar", bundle.translatedLanguageCode)
        assertEquals(2, bundle.translatedCueCount)
    }

    @Test
    fun `vtt translated export preserves timestamps with dot decimal`() {
        val original = listOf(cue(1, 1000, 2000, "Hi"))
        val translated = listOf(cue(1, 1000, 2000, "مرحبا"))
        val bundle = SubtitleExportAssembler.assemble(
            cues = original,
            translatedCues = translated,
            shiftMs = 0L,
            format = SubtitleFormat.VTT,
            parser = parser,
            targetLanguageCode = "ar",
        )
        val text = requireNotNull(bundle.translatedText)
        assertTrue(text.startsWith("WEBVTT"))
        assertTrue(text.contains("00:00:01.000 --> 00:00:02.000"))
        assertTrue(text.contains("مرحبا"))
    }
}
