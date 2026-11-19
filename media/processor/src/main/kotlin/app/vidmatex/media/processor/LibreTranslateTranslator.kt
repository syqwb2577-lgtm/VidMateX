package app.vidmatex.media.processor

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.model.SubtitleCue
import app.vidmatex.core.model.TranslationProvider
import app.vidmatex.core.network.LibreTranslateApi
import app.vidmatex.core.network.LibreTranslateRequest
import app.vidmatex.core.network.RetrofitFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Translation via a self-hosted LibreTranslate endpoint. Constructed lazily by
 * [TranslatorRegistry] with the base URL taken from user settings (never hardcoded); only
 * https endpoints are accepted ([RetrofitFactory.libreTranslateApi] returns null otherwise).
 *
 * Batching: cues are sent in small sequential chunks joined by a unique delimiter to respect
 * typical self-hosted rate limits. If the response does not round-trip the delimiter
 * (server mangled/normalized it), the chunk falls back to per-cue requests. A failure in any
 * chunk aborts the whole call: [AppResult.Error] with `recoverable = true` is returned and
 * NO partial cue list surfaces. Returned cues keep index/startMs/endMs; blank translations
 * fall back to the original cue lines via [CueTranslationMapper].
 *
 * The request/response data model (`LibreTranslateRequest`/`LibreTranslateResponse`) lives in
 * core:network (D-owned) and is unchanged here; only call-site behavior differs.
 */
class LibreTranslateTranslator(
    private val retrofitFactory: RetrofitFactory,
    private val baseUrl: String,
) : SubtitleTranslator {
    override val provider: TranslationProvider = TranslationProvider.LIBRE_TRANSLATE

    override fun isConfigured(): Boolean = api() != null

    private fun api(): LibreTranslateApi? = retrofitFactory.libreTranslateApi(baseUrl.trim())

    override suspend fun translate(
        cues: List<SubtitleCue>,
        sourceLang: String?,
        targetLang: String,
    ): AppResult<List<SubtitleCue>> {
        if (cues.isEmpty()) return AppResult.Success(emptyList())
        val api = api() ?: return AppResult.Error(
            "LibreTranslate endpoint is not configured or is not a valid https URL.",
            recoverable = true,
        )
        val source = sourceLang?.lowercase()?.take(2) ?: "en"
        val target = targetLang.lowercase().take(2)
        return try {
            // Retry the whole batch on transient network/HTTP failures (429/5xx, timeouts,
            // connection errors). Permanent failures (bad request, unsupported language)
            // abort immediately; partial cue lists are never surfaced on error.
            TranslationRetry.withRetry {
                val result = ArrayList<SubtitleCue>(cues.size)
                cues.chunked(BATCH_SIZE).forEach { chunk ->
                    result.addAll(translateChunk(api, chunk, source, target))
                }
                result
            }.let { AppResult.Success(it) }
        } catch (e: Exception) {
            AppResult.Error(
                "LibreTranslate translation failed: ${e.message ?: e.javaClass.simpleName}",
                recoverable = TranslationRetry.shouldRetry(e),
            )
        }
    }

    private suspend fun translateChunk(
        api: LibreTranslateApi,
        chunk: List<SubtitleCue>,
        source: String,
        target: String,
    ): List<SubtitleCue> = withContext(Dispatchers.IO) {
        val joined = chunk.joinToString(DELIMITER) { it.lines.joinToString("\n") }
        val response = translateOne(api, joined, source, target)
        val parts = response.split(DELIMITER).map { it.trim() }
        if (parts.size != chunk.size) {
            // Delimiter was mangled by the server; guard: fall back to per-cue translation
            // for this chunk. Per-cue failure throws and aborts the whole batch (no partial).
            chunk.map { cue ->
                val text = translateOne(api, cue.lines.joinToString("\n"), source, target)
                cue.copy(lines = CueTranslationMapper.splitToLines(text, cue.lines.size, cue.lines))
            }
        } else {
            chunk.zip(parts).map { (cue, text) ->
                cue.copy(lines = CueTranslationMapper.splitToLines(text, cue.lines.size, cue.lines))
            }
        }
    }

    /**
     * Single LibreTranslate POST. Maps transient HTTP failures (429 / 5xx) to
     * [TranslationRetry.TransientHttpException] so the retry wrapper retries; 4xx
     * (bad request / unsupported language) propagates as a permanent error.
     */
    private suspend fun translateOne(
        api: LibreTranslateApi,
        q: String,
        source: String,
        target: String,
    ): String {
        val response = try {
            api.translate(LibreTranslateRequest(q = q, source = source, target = target, format = "text"))
        } catch (http: retrofit2.HttpException) {
            val code = http.code()
            if (code == 429 || code in 500..599) {
                throw TranslationRetry.TransientHttpException(code, "LibreTranslate HTTP $code")
            }
            throw http
        }
        return response.translatedText
    }

    companion object {
        private const val DELIMITER = "\n---\n"
        private const val BATCH_SIZE = 20
    }
}
