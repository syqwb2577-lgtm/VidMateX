package app.vidmatex.media.processor

import app.vidmatex.core.model.AppSettings
import app.vidmatex.core.model.TranslationProvider
import app.vidmatex.core.network.RetrofitFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects the active [SubtitleTranslator] for the user's chosen provider. ML Kit and NoOp are
 * injected singletons; LibreTranslate is constructed lazily from the current settings (base
 * URL may change, so the instance is never cached at the singleton level).
 *
 * Resolution rules:
 * - [TranslationProvider.NONE] (or any unrecognized value) → [NoOpTranslator]
 *   (`isConfigured() = false`, clear actionable message).
 * - [TranslationProvider.ML_KIT] → [MlKitTranslator].
 * - [TranslationProvider.LIBRE_TRANSLATE] → [NoOpTranslator] while `libreTranslateBaseUrl`
 *   is blank; otherwise a [LibreTranslateTranslator] whose `isConfigured()` additionally
 *   requires the URL to be a valid https base URL.
 */
@Singleton
class TranslatorRegistry @Inject constructor(
    private val mlKit: MlKitTranslator,
    private val noOp: NoOpTranslator,
    private val retrofitFactory: RetrofitFactory,
) {
    fun forProvider(provider: TranslationProvider, settings: AppSettings): SubtitleTranslator =
        when (provider) {
            TranslationProvider.NONE -> noOp
            TranslationProvider.ML_KIT -> mlKit
            TranslationProvider.LIBRE_TRANSLATE ->
                if (settings.libreTranslateBaseUrl.isBlank()) {
                    noOp
                } else {
                    LibreTranslateTranslator(retrofitFactory, settings.libreTranslateBaseUrl)
                }
            else -> noOp
        }
}
