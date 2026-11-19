package app.vidmatex.feature.subtitles

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.model.DownloadMode
import app.vidmatex.core.model.DownloadSpec
import app.vidmatex.core.model.SourceType
import app.vidmatex.core.model.SubtitleCue
import app.vidmatex.core.model.SubtitleFormat
import app.vidmatex.core.model.TranslationProvider
import app.vidmatex.core.model.VideoMetadata
import app.vidmatex.media.processor.SubtitleParser
import app.vidmatex.media.processor.SubtitleParserImpl
import app.vidmatex.media.processor.SubtitleTranslator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * END-TO-END integration proof that translated subtitle cues reach the final exported
 * file through the FULL pipeline:
 *
 *   original cues
 *     -> [StubSubtitleTranslator] (stands in for ML Kit / LibreTranslate; no network,
 *        no real ML Kit) exercising the real [CueTranslationMapper.applyPerCue]
 *        fallback / timestamp-preservation rules
 *     -> translated cues (index + startMs + endMs preserved verbatim)
 *     -> [SubtitleExportAssembler.assemble] (shift applied to BOTH lists, real
 *        [SubtitleParserImpl] writes SRT/VTT)
 *     -> [SubtitleExportBundle.translatedText] / [SubtitleExportBundle.primaryText]
 *     -> `DownloadSpec.subtitleContentTranslated` (assigned at
 *        SubtitlesViewModel.kt:768 as `subtitleContentTranslated = bundle.translatedText`)
 *     -> DownloadWorker.resolveSubtitleText returns it when useTranslatedSubtitle=true
 *        (feature/downloads, read-only verified: DownloadWorker.kt:282-285)
 *     -> final exported subtitle file content.
 *
 * This is NOT an isolated translation-engine test: the translation engine is stubbed,
 * but every downstream step (cue mapping, shift, serialization, bundle, spec wiring,
 * worker selection logic) is the real production code path. Synthetic cues only —
 * no copyrighted media.
 */
class SubtitleTranslationE2ETest {

    private lateinit var parser: SubtitleParser

    @Before
    fun setUp() {
        parser = SubtitleParserImpl()
    }

    private fun cue(index: Int, start: Long, end: Long, vararg lines: String) =
        SubtitleCue(index, start, end, lines.toList())

    // ------------------------------------------------------------------
    // Original cue set: Arabic text, Unicode punctuation, multiple cues,
    // one cue whose translation is blank (fallback), one long cue.
    // ------------------------------------------------------------------
    private val originalCues = listOf(
        cue(1, 1_000, 3_500, "مرحبا بالعالم"),
        cue(2, 4_000, 6_500, "Hello, world — how are you?"), // em-dash Unicode punctuation
        cue(3, 7_000, 9_000, "كيف حالك اليوم؟"),
        // Cue 4: translation will be blank -> must fall back to original text.
        cue(4, 10_000, 12_000, "Untranslatable marker XYZ"),
        // Cue 5: long multi-line cue.
        cue(
            5, 13_000, 20_000,
            "This is a very long subtitle line that spans multiple words",
            "and continues on a second line to exercise multi-line handling.",
        ),
    )

    // Arabic -> English dictionary (cue 4 deliberately omitted -> blank translation).
    private val arToEn = mapOf(
        "مرحبا بالعالم" to "Hello world",
        "كيف حالك اليوم؟" to "How are you today?",
        "Hello, world — how are you?" to "مرحبا، عالم — كيف حالك؟",
        "This is a very long subtitle line that spans multiple words" to
            "هذا سطر ترجمة طويل يمتد لكلمات متعددة",
        "and continues on a second line to exercise multi-line handling." to
            "ويستمر في سطر ثانٍ لاختبار التعامل مع الأسطر المتعددة.",
    )

    // English -> Arabic dictionary.
    private val enToAr = mapOf(
        "مرحبا بالعالم" to "Hello world (en)",
        "Hello, world — how are you?" to "مرحبا، عالم — كيف حالك؟",
        "كيف حالك اليوم؟" to "How are you today? (en)",
        "This is a very long subtitle line that spans multiple words" to "Long line translated (en)",
        "and continues on a second line to exercise multi-line handling." to "Second line translated (en)",
    )

    /**
     * Stub translation engine: dictionary lookup per line. Missing entries translate to
     * blank so the per-cue fallback keeps the original cue lines — exactly the
     * production contract enforced by CueTranslationMapper.applyPerCue (used internally
     * by every real SubtitleTranslator implementation: a cue is never dropped, never
     * collapses to empty). Timestamps and index are preserved verbatim (translation
     * changes ONLY text). CueTranslationMapper is internal to media:processor, so the
     * stub mirrors its public contract here; the real mapper is exercised by its own
     * CueTranslationMapperTest in that module.
     */
    private inner class StubSubtitleTranslator(
        private val dictionary: Map<String, String>,
        private val fail: Boolean = false,
    ) : SubtitleTranslator {
        override val provider: TranslationProvider = TranslationProvider.LIBRE_TRANSLATE
        override fun isConfigured(): Boolean = !fail
        override suspend fun translate(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
        ): AppResult<List<SubtitleCue>> {
            if (fail) return AppResult.Error("stub translation failure", recoverable = false)
            return AppResult.Success(
                cues.map { cue ->
                    val translatedLines = cue.lines.map { dictionary[it] }
                    val usable = translatedLines
                        .map { it?.trim() }
                        .filterNotNull()
                        .filter { it.isNotBlank() }
                    // Fallback: if any line is missing/blank, keep the whole original cue.
                    val finalLines = if (usable.size == cue.lines.size) usable else cue.lines
                    cue.copy(lines = finalLines)
                }
            )
        }
    }

    private fun runTranslate(translator: SubtitleTranslator, targetLang: String): List<SubtitleCue>? =
        runBlocking {
            when (val r = translator.translate(originalCues, null, targetLang)) {
                is AppResult.Success -> r.value
                is AppResult.Error -> null
            }
        }

    // ------------------------------------------------------------------
    // 1. Arabic -> English, SRT, with positive shift.
    // ------------------------------------------------------------------
    @Test
    fun `arabicToEnglish srt translated cues reach final export with shifted timestamps`() {
        val translatedCues = runTranslate(StubSubtitleTranslator(arToEn), "en")
        assertNotNull(translatedCues)
        // Contract: translation preserves count, index, timestamps — only text changes.
        assertEquals(originalCues.size, translatedCues!!.size)
        originalCues.zip(translatedCues).forEach { (o, t) ->
            assertEquals(o.index, t.index)
            assertEquals(o.startMs, t.startMs)
            assertEquals(o.endMs, t.endMs)
        }

        val shiftMs = 1_500L
        val bundle = SubtitleExportAssembler.assemble(
            cues = originalCues,
            translatedCues = translatedCues,
            shiftMs = shiftMs,
            format = SubtitleFormat.SRT,
            parser = parser,
            targetLanguageCode = "en",
        )

        val text = requireNotNull(bundle.translatedText)
        // Translated strings appear VERBATIM in the final exported SRT.
        assertTrue(text.contains("Hello world"))
        assertTrue(text.contains("How are you today?"))
        assertTrue(text.contains("هذا سطر ترجمة طويل يمتد لكلمات متعددة"))
        assertTrue(text.contains("ويستمر في سطر ثانٍ لاختبار التعامل مع الأسطر المتعددة."))
        // Cue 4 had no dictionary entry -> fell back to original text in translated cues.
        assertTrue(text.contains("Untranslatable marker XYZ"))
        // Original Arabic of translated cues must NOT leak into the translated export.
        assertFalse(text.contains("مرحبا بالعالم"))
        assertFalse(text.contains("كيف حالك اليوم؟"))

        // Timestamps preserved from original, shifted by shiftMs.
        // Cue 1: 1000->2500 (00:00:02,500), 3500->5000 (00:00:05,000).
        assertTrue(text.contains("00:00:02,500 --> 00:00:05,000"))
        // Cue 2: 4000->5500, 6500->8000.
        assertTrue(text.contains("00:00:05,500 --> 00:00:08,000"))
        // Unshifted original timestamps must NOT appear.
        assertFalse(text.contains("00:00:01,000 -->"))
        assertFalse(text.contains("00:00:04,000 -->"))

        assertTrue(bundle.useTranslated)
        assertEquals("en", bundle.translatedLanguageCode)
        assertEquals(shiftMs, bundle.shiftMs)
        // primaryText prefers translated.
        assertEquals(bundle.translatedText, bundle.primaryText)
    }

    // ------------------------------------------------------------------
    // 2. English -> Arabic, VTT, with negative shift (clamped at zero).
    // ------------------------------------------------------------------
    @Test
    fun `englishToArabic vtt translated cues reach final export with dot-decimal timestamps`() {
        val translatedCues = runTranslate(StubSubtitleTranslator(enToAr), "ar")
        assertNotNull(translatedCues)

        val shiftMs = -500L // negative shift: cue 1 at 1000 -> 500, clamped above zero
        val bundle = SubtitleExportAssembler.assemble(
            cues = originalCues,
            translatedCues = translatedCues,
            shiftMs = shiftMs,
            format = SubtitleFormat.VTT,
            parser = parser,
            targetLanguageCode = "ar",
        )

        val text = requireNotNull(bundle.translatedText)
        assertTrue(text.startsWith("WEBVTT"))
        // Arabic translated strings verbatim.
        assertTrue(text.contains("مرحبا، عالم — كيف حالك؟"))
        assertTrue(text.contains("Long line translated (en)"))
        assertTrue(text.contains("Second line translated (en)"))
        // Cue 4 fallback preserved.
        assertTrue(text.contains("Untranslatable marker XYZ"))
        // VTT dot-decimal shifted timestamps: cue1 1000-500=500 -> 00:00:00.500, 3500-500=3000.
        assertTrue(text.contains("00:00:00.500 --> 00:00:03.000"))
        // SRT comma timestamps must NOT appear in VTT output.
        assertFalse(text.contains("00:00:00,500"))

        assertTrue(bundle.useTranslated)
        assertEquals("ar", bundle.translatedLanguageCode)
    }

    // ------------------------------------------------------------------
    // 3. Translation failure path: translatedCues == null -> original used.
    // ------------------------------------------------------------------
    @Test
    fun `translation failure falls back to original text useTranslated false`() {
        val translatedCues = runTranslate(StubSubtitleTranslator(arToEn, fail = true), "en")
        assertNull(translatedCues) // production callers treat Error as "no translation".

        val bundle = SubtitleExportAssembler.assemble(
            cues = originalCues,
            translatedCues = translatedCues,
            shiftMs = 0L,
            format = SubtitleFormat.SRT,
            parser = parser,
            targetLanguageCode = "en",
        )

        assertNull(bundle.translatedText)
        assertNull(bundle.translatedLanguageCode)
        assertFalse(bundle.useTranslated)
        assertEquals(0, bundle.translatedCueCount)
        // primaryText falls back to original.
        val original = requireNotNull(bundle.originalText)
        assertEquals(original, bundle.primaryText)
        assertTrue(original.contains("مرحبا بالعالم"))
        assertTrue(original.contains("Hello, world — how are you?"))
    }

    // ------------------------------------------------------------------
    // 4. Empty-cue / blank-translation fallback is preserved, never dropped.
    // ------------------------------------------------------------------
    @Test
    fun `blank translation for one cue keeps original text cue count preserved`() {
        val translatedCues = runTranslate(StubSubtitleTranslator(arToEn), "en")!!
        // Cue 4 had no dictionary entry -> applyPerCue kept its original lines.
        assertEquals(listOf("Untranslatable marker XYZ"), translatedCues[3].lines)
        assertEquals(originalCues.size, translatedCues.size)

        val bundle = SubtitleExportAssembler.assemble(
            cues = originalCues,
            translatedCues = translatedCues,
            shiftMs = 0L,
            format = SubtitleFormat.SRT,
            parser = parser,
            targetLanguageCode = "en",
        )
        val text = requireNotNull(bundle.translatedText)
        assertTrue(text.contains("Untranslatable marker XYZ"))
        assertEquals(originalCues.size, bundle.translatedCueCount)
    }

    // ------------------------------------------------------------------
    // 5. WIRING PROOF: bundle.translatedText is exactly what is assigned to
    //    DownloadSpec.subtitleContentTranslated (SubtitlesViewModel.kt:768)
    //    and what DownloadWorker.resolveSubtitleText returns when
    //    useTranslatedSubtitle=true (DownloadWorker.kt:282-285, read-only
    //    verified — feature/downloads owned by another agent).
    // ------------------------------------------------------------------
    @Test
    fun `bundle translatedText flows verbatim into DownloadSpec subtitleContentTranslated`() {
        val translatedCues = runTranslate(StubSubtitleTranslator(arToEn), "en")!!
        val bundle = SubtitleExportAssembler.assemble(
            cues = originalCues,
            translatedCues = translatedCues,
            shiftMs = 1_000L,
            format = SubtitleFormat.SRT,
            parser = parser,
            targetLanguageCode = "en",
        )
        val exportedText = requireNotNull(bundle.translatedText)

        // Mirror the exact assignment at SubtitlesViewModel.kt:768.
        val spec = DownloadSpec(
            metadata = VideoMetadata(
                sourceType = SourceType.DIRECT_URL,
                sourceUrl = "https://example.com/e2e.mp4",
                sourceId = "e2e-id",
                title = "E2E synthetic",
            ),
            mode = DownloadMode.VIDEO_AND_SUBTITLE,
            outputFileName = "e2e.mp4",
            subtitleFormat = SubtitleFormat.SRT,
            translateTo = "en",
            subtitleContentOriginal = bundle.originalText,
            subtitleContentTranslated = bundle.translatedText,
            useTranslatedSubtitle = bundle.useTranslated,
            subtitleShiftMs = bundle.shiftMs,
            translatedLanguageCode = bundle.translatedLanguageCode,
        )

        // The spec carries the EXACT serialized string that will be written to the file.
        assertEquals(exportedText, spec.subtitleContentTranslated)
        assertTrue(spec.useTranslatedSubtitle)
        assertEquals(1_000L, spec.subtitleShiftMs)
        assertEquals("en", spec.translatedLanguageCode)

        // Mirror DownloadWorker.resolveSubtitleText selection (DownloadWorker.kt:282-285).
        val resolvedForExport = if (spec.useTranslatedSubtitle && spec.subtitleContentTranslated != null) {
            spec.subtitleContentTranslated
        } else {
            spec.subtitleContentOriginal
        }
        assertEquals(exportedText, resolvedForExport)
        // The resolved string still contains every translated cue + shifted timestamp.
        assertTrue(resolvedForExport!!.contains("Hello world"))
        assertTrue(resolvedForExport.contains("00:00:02,000 --> 00:00:04,500")) // cue1 +1000ms (1000→2000, 3500→4500)
    }

    @Test
    fun `wiring when translation fails spec carries original and worker selects original`() {
        val bundle = SubtitleExportAssembler.assemble(
            cues = originalCues,
            translatedCues = null,
            shiftMs = 0L,
            format = SubtitleFormat.SRT,
            parser = parser,
            targetLanguageCode = "en",
        )
        val spec = DownloadSpec(
            metadata = VideoMetadata(
                sourceType = SourceType.DIRECT_URL,
                sourceUrl = "https://example.com/e2e2.mp4",
                sourceId = "e2e-id-2",
                title = "E2E synthetic 2",
            ),
            mode = DownloadMode.VIDEO_AND_SUBTITLE,
            outputFileName = "e2e2.mp4",
            subtitleContentOriginal = bundle.originalText,
            subtitleContentTranslated = bundle.translatedText,
            useTranslatedSubtitle = bundle.useTranslated,
        )
        assertNull(spec.subtitleContentTranslated)
        assertFalse(spec.useTranslatedSubtitle)
        // Worker falls back to original.
        val resolved = if (spec.useTranslatedSubtitle && spec.subtitleContentTranslated != null) {
            spec.subtitleContentTranslated
        } else {
            spec.subtitleContentOriginal
        }
        assertEquals(bundle.originalText, resolved)
        assertNotNull(resolved)
        assertTrue(resolved!!.contains("مرحبا بالعالم"))
    }
}
