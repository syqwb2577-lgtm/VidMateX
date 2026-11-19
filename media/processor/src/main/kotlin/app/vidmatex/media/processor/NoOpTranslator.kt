package app.vidmatex.media.processor

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.model.SubtitleCue
import app.vidmatex.core.model.TranslationProvider

/**
 * Fallback when no translation provider is configured. Never translates; surfaces a clear
 * actionable message so the user knows to enable ML Kit or set a LibreTranslate endpoint.
 */
class NoOpTranslator : SubtitleTranslator {
    override val provider: TranslationProvider = TranslationProvider.NONE
    override fun isConfigured(): Boolean = false
    override suspend fun translate(
        cues: List<SubtitleCue>,
        sourceLang: String?,
        targetLang: String,
    ): AppResult<List<SubtitleCue>> = AppResult.Error(
        "Translation not configured. Enable ML Kit on-device translation or set a LibreTranslate endpoint in Settings.",
        recoverable = true,
    )
}
