package app.vidmatex.media.processor

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.model.SubtitleCue
import app.vidmatex.core.model.TranslationProvider
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device translation via Google ML Kit. Free and offline once the target language model
 * is downloaded. [isConfigured] returns true because no server endpoint is required, but the
 * first translation of a new language triggers a one-time model download (requires network).
 *
 * Behavior contract: cues are translated per cue (no cross-cue batching); the returned cues
 * keep index, startMs and endMs exactly — only [SubtitleCue.lines] change. A blank/empty
 * translation falls back to the original cue lines (mapper); any model-download or
 * translation failure returns [AppResult.Error] with `recoverable = true` and never a partial
 * cue list. Line-count normalization lives in [CueTranslationMapper] (JVM-pure, tested).
 */
class MlKitTranslator @Inject constructor() : SubtitleTranslator {
    override val provider: TranslationProvider = TranslationProvider.ML_KIT
    override fun isConfigured(): Boolean = true // on-device; model download handled in translate()

    override suspend fun translate(
        cues: List<SubtitleCue>,
        sourceLang: String?,
        targetLang: String,
    ): AppResult<List<SubtitleCue>> {
        if (cues.isEmpty()) return AppResult.Success(emptyList())
        val source = sourceLang?.lowercase()?.take(2) ?: "en" // ML Kit requires an explicit source
        val target = targetLang.lowercase().take(2)
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        val client = Translation.getClient(options)
        return try {
            // Download the target model if not already present (one-time, network required).
            val conditions = DownloadConditions.Builder().build()
            client.downloadModelIfNeeded(conditions).await()
            // Per-cue translation; mapper preserves timestamps/index and falls back on blank.
            val mapped = cues.map { cue ->
                val joined = cue.lines.joinToString("\n")
                val translated = client.translate(joined).await()
                cue.copy(
                    lines = CueTranslationMapper.splitToLines(translated, cue.lines.size, cue.lines),
                )
            }
            AppResult.Success(mapped)
        } catch (e: Exception) {
            AppResult.Error(
                "ML Kit translation failed (model download or translation error): " +
                    (e.message ?: e.javaClass.simpleName),
                recoverable = true,
            )
        } finally {
            client.close()
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
