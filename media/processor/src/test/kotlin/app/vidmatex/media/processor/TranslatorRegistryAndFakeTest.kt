package app.vidmatex.media.processor

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.model.AppSettings
import app.vidmatex.core.model.SubtitleCue
import app.vidmatex.core.model.TranslationProvider
import app.vidmatex.core.network.RetrofitFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Registry resolution rules plus a fake [SubtitleTranslator] stub (no ML Kit execution,
 * no real network). JVM-pure; pending CI execution.
 */
class TranslatorRegistryAndFakeTest {

    private lateinit var registry: TranslatorRegistry
    private lateinit var noOp: NoOpTranslator

    @Before
    fun setUp() {
        noOp = NoOpTranslator()
        val factory = RetrofitFactory(OkHttpClient.Builder().build(), Json { ignoreUnknownKeys = true })
        registry = TranslatorRegistry(MlKitTranslator(), noOp, factory)
    }

    @Test
    fun `NONE resolves to NoOp which is not configured and fails with a clear recoverable message`() = runBlocking {
        val settings = AppSettings(translationProvider = TranslationProvider.NONE)
        val translator = registry.forProvider(TranslationProvider.NONE, settings)
        assertSame(noOp, translator)
        assertFalse(translator.isConfigured())
        val result = translator.translate(emptyList(), null, "en")
        assertTrue(result is AppResult.Error)
        val error = result as AppResult.Error
        assertTrue(error.recoverable)
        assertTrue(error.message.contains("not configured", ignoreCase = true))
    }

    @Test
    fun `ML_KIT resolves to MlKitTranslator`() {
        val settings = AppSettings(translationProvider = TranslationProvider.ML_KIT)
        val translator = registry.forProvider(TranslationProvider.ML_KIT, settings)
        assertTrue(translator is MlKitTranslator)
        assertEquals(TranslationProvider.ML_KIT, translator.provider)
    }

    @Test
    fun `LIBRE_TRANSLATE with blank base url falls back to NoOp and reports unconfigured`() {
        val settings = AppSettings(translationProvider = TranslationProvider.LIBRE_TRANSLATE, libreTranslateBaseUrl = "")
        val translator = registry.forProvider(TranslationProvider.LIBRE_TRANSLATE, settings)
        assertSame(noOp, translator)
        assertFalse(translator.isConfigured())
    }

    @Test
    fun `LIBRE_TRANSLATE with valid https url yields a configured translator`() {
        val settings = AppSettings(
            translationProvider = TranslationProvider.LIBRE_TRANSLATE,
            libreTranslateBaseUrl = "https://translate.example.com",
        )
        val translator = registry.forProvider(TranslationProvider.LIBRE_TRANSLATE, settings)
        assertTrue(translator is LibreTranslateTranslator)
        assertTrue(translator.isConfigured())
    }

    @Test
    fun `LIBRE_TRANSLATE with non-https or malformed url reports unconfigured`() {
        val http = AppSettings(libreTranslateBaseUrl = "http://127.0.0.1:9999")
        assertFalse(registry.forProvider(TranslationProvider.LIBRE_TRANSLATE, http).isConfigured())
        val garbage = AppSettings(libreTranslateBaseUrl = "not a url")
        assertFalse(registry.forProvider(TranslationProvider.LIBRE_TRANSLATE, garbage).isConfigured())
    }

    @Test
    fun `fake stub output keeps index and timestamps and only changes lines`() = runBlocking {
        val cues = listOf(
            SubtitleCue(1, 1_000L, 2_000L, listOf("Hello")),
            SubtitleCue(2, 3_000L, 4_000L, listOf("World")),
        )
        val mapped = CueTranslationMapper.applyPerCue(
            cues,
            listOf(listOf("مرحبا"), null),
        )
        val stub = FakeTranslator(TranslationProvider.ML_KIT, configured = true, AppResult.Success(mapped))
        val result = stub.translate(cues, "en", "ar")
        assertTrue(result is AppResult.Success)
        val out = (result as AppResult.Success).value
        assertEquals(2, out.size)
        assertEquals(1, out[0].index)
        assertEquals(1_000L, out[0].startMs)
        assertEquals(2_000L, out[0].endMs)
        assertEquals(listOf("مرحبا"), out[0].lines)
        assertEquals(2, out[1].index)
        assertEquals(3_000L, out[1].startMs)
        assertEquals(4_000L, out[1].endMs)
        assertEquals(cues[1].lines, out[1].lines)
    }

    @Test
    fun `fake stub failure surfaces recoverable error and no partial cue list`() = runBlocking {
        val failure = AppResult.Error("batch failed", recoverable = true)
        val stub = FakeTranslator(TranslationProvider.LIBRE_TRANSLATE, configured = true, failure)
        val result = stub.translate(emptyList(), "en", "ar")
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).recoverable)
    }

    /** Minimal in-test stub; stands in for ML Kit / LibreTranslate without device or network. */
    private class FakeTranslator(
        override val provider: TranslationProvider,
        private val configured: Boolean,
        private val outcome: AppResult<List<SubtitleCue>>,
    ) : SubtitleTranslator {
        override fun isConfigured(): Boolean = configured
        override suspend fun translate(
            cues: List<SubtitleCue>,
            sourceLang: String?,
            targetLang: String,
        ): AppResult<List<SubtitleCue>> = outcome
    }
}
