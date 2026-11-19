package app.vidmatex.core.network

import app.vidmatex.core.model.MediaTrack
import app.vidmatex.core.model.SubtitleTrack
import app.vidmatex.core.model.VideoMetadata
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Optional, user-configurable self-hosted resolver endpoint (e.g. a small service wrapping
 * yt-dlp). The APK never ships a hardcoded endpoint or API key. When baseUrl is blank the
 * adapters report "resolver not configured" instead of failing silently.
 *
 * Serialization tolerance policy (CONTRACTS section 10):
 *  - Unknown JSON keys are ignored (`Json.ignoreUnknownKeys = true`); missing optional
 *    fields deserialize to stable defaults, so older/minimal backends keep working.
 *  - Malformed or shape-mismatched JSON throws [kotlinx.serialization.SerializationException]
 *    from the converter; `safeApiCall` maps it to [BackendException.MalformedBody] — a
 *    typed `AppResult.Error`, never an uncaught crash.
 *  - Response DTOs carry media metadata only; no key material or secret value is ever
 *    deserialized into these models. Outgoing secret headers (X-API-Key) travel via
 *    [ApiKeyInterceptor] and are never included in request/response bodies or errors.
 */
interface BackendApi {
    /**
     * Liveness probe. The backend exempts this route from rate limiting and auth,
     * so the Settings "Test connection" flow can reach it even before the key
     * matches. Missing fields deserialize to defaults (tolerant to older backends).
     */
    @GET("health")
    suspend fun health(): HealthResponse

    @POST("extract")
    suspend fun extract(@Body request: ExtractRequest): ExtractResponse

    @POST("subtitle")
    suspend fun subtitle(@Body request: SubtitleRequest): SubtitleResponse
}

/**
 * GET /health payload. All fields have defaults so an older/minimal backend that
 * omits `version` or `resolvers` still parses successfully (NetworkModule's Json
 * sets ignoreUnknownKeys + coerceInputValues).
 */
@Serializable
data class HealthResponse(
    val status: String = "",
    val version: String? = null,
    val resolvers: List<String> = emptyList(),
)

@Serializable
data class ExtractRequest(val url: String)
@Serializable
data class ExtractResponse(
    val metadata: VideoMetadata,
    val formats: List<MediaTrack> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    /** trackId -> direct download URL (expiring URLs are fine; consumed immediately). */
    val streamUrls: Map<String, String> = emptyMap(),
    /**
     * False when the platform is recognized but no compliant download method is configured.
     * True by default (old backends that omit this field are treated as download-capable).
     */
    val downloadAvailable: Boolean = true,
    /** Human-readable note from the backend (e.g. "configure a compliant resolver to enable downloads"). */
    val message: String? = null,
)
/**
 * POST /subtitle request.
 *
 * CONTRACT NOTE (backend schemas.SubtitleRequest): `format` must match ^(srt|vtt|ass)$
 * (lowercase). The Android [app.vidmatex.core.model.SubtitleFormat] enum is uppercase
 * (SRT/VTT/ASS). Callers MUST normalize via [SubtitleRequest.forFormat] or pass a
 * lowercase string; an uppercase value is rejected by the backend with HTTP 422
 * INVALID_URL. Recorded in handoff/contract_mismatches_client.md.
 */
@Serializable
data class SubtitleRequest(val url: String, val trackId: String, val format: String) {
    companion object {
        val ALLOWED_FORMATS = setOf("srt", "vtt", "ass")

        /** Build a request from the Android SubtitleFormat enum, normalizing `format` to
         *  the lowercase value the backend contract requires. */
        fun forFormat(
            url: String,
            trackId: String,
            format: app.vidmatex.core.model.SubtitleFormat,
        ): SubtitleRequest = SubtitleRequest(url, trackId, format.name.lowercase())
    }
}
@Serializable
data class SubtitleResponse(val content: String, val format: String)

/** LibreTranslate (self-hostable) translation endpoint. */
interface LibreTranslateApi {
    @POST("translate")
    suspend fun translate(@Body request: LibreTranslateRequest): LibreTranslateResponse
}
@Serializable
data class LibreTranslateRequest(
    val q: String,
    val source: String,
    val target: String,
    val format: String = "text",
    /** Optional provider key for public LibreTranslate instances; null omits the field.
     *  Sent only over https and never logged or echoed into error messages. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val api_key: String? = null,
)
@Serializable
data class LibreTranslateResponse(val translatedText: String)
