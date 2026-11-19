package app.vidmatex.feature.subtitles.transcription

import app.vidmatex.media.processor.WhisperEndpointValidator

/**
 * Whisper integration contract owned by the `feature:subtitles` module.
 *
 * Responsibility split (no duplication of the real HTTP stack, which lives in
 * `media:processor` `WhisperSpeechToText`):
 *  - [WhisperSecret] keeps the API key in memory only and never lets it leak through
 *    `toString()` / logging; persistence encryption happens in `feature:settings`
 *    (`AndroidKeystoreSecretCipher`); this layer only consumes the decrypted value
 *    supplied by `SettingsRepository.current().whisperApiKey`.
 *  - [WhisperEndpointConfig] performs pre-flight validation (URL rules reusing the
 *    public `WhisperEndpointValidator`; key-presence note; caller-provided file
 *    existence) before any request is built.
 *  - [WhisperRequestBuilder] turns a validated config into an immutable
 *    [WhisperRequestSpec] (URL, Authorization header when present, multipart field
 *    list). The actual multipart/OkHttp call remains in `media:processor`; this spec
 *    is the pure, JVM-testable shape of that request.
 *
 * Nothing here performs network I/O and nothing here logs a key.
 */

/**
 * In-memory-only API key wrapper. The [value] is accessible solely to the request
 * layer that attaches the Authorization header; [toString] always renders a redacted
 * placeholder so the secret cannot appear in logs, crash reports, or WorkManager
 * output data.
 */
class WhisperSecret private constructor(private val value: String) {
    val present: Boolean get() = value.isNotBlank()

    /** Non-null header value only when a key is present; never null fields otherwise. */
    fun authorizationHeader(): Pair<String, String>? =
        if (present) "Authorization" to "Bearer $value" else null

    /** Diagnostic label for logs/UI: presence only, never the secret. */
    fun describe(): String = if (present) "key:present" else "key:absent"

    override fun toString(): String = "WhisperSecret(${describe()})"

    companion object {
        fun from(raw: String?): WhisperSecret = WhisperSecret(raw.orEmpty().trim())
    }
}

/** Severity of a pre-flight configuration issue. */
enum class ConfigSeverity { BLOCKING, WARNING }

/** One validated configuration problem; messages never contain the API key. */
data class ConfigIssue(
    val severity: ConfigSeverity,
    val code: String,
    val message: String,
)

/**
 * Validated snapshot of everything a transcription request needs except the local
 * file bytes (those are checked by the worker from the absolute path).
 *
 * @param endpoint configured OpenAI-compatible base URL (e.g. `https://host/v1`)
 * @param model Whisper model name sent as the `model` form field
 * @param secret decrypted API key from the secure settings store; may be absent for
 *        keyless self-hosted servers (then a WARNING, not a BLOCKING error)
 */
data class WhisperEndpointConfig(
    val endpoint: String,
    val model: String,
    val secret: WhisperSecret,
) {
    /**
     * Pre-flight validation performed before enqueueing any worker:
     *  - BLOCKING: endpoint blank/invalid, model blank, local file missing/empty;
     *  - WARNING: API key absent (self-hosted servers may be keyless; hosted
     *    providers such as OpenAI would then fail auth and surface HTTP 401/403).
     */
    fun validate(fileExists: Boolean, fileLength: Long): List<ConfigIssue> {
        val issues = mutableListOf<ConfigIssue>()
        if (!WhisperEndpointValidator.isValid(endpoint)) {
            issues += ConfigIssue(
                ConfigSeverity.BLOCKING,
                "invalid_endpoint",
                "Whisper endpoint is blank or not a valid https (loopback http) URL.",
            )
        }
        if (model.isBlank()) {
            issues += ConfigIssue(
                ConfigSeverity.BLOCKING,
                "missing_model",
                "Whisper model name is empty.",
            )
        }
        if (!secret.present) {
            issues += ConfigIssue(
                ConfigSeverity.WARNING,
                "missing_key",
                "No Whisper API key set. Keyless self-hosted endpoints work; hosted providers may reject the request with HTTP 401/403.",
            )
        }
        if (!fileExists) {
            issues += ConfigIssue(
                ConfigSeverity.BLOCKING,
                "missing_file",
                "Local audio/video file does not exist.",
            )
        } else if (fileLength <= 0L) {
            issues += ConfigIssue(
                ConfigSeverity.BLOCKING,
                "empty_file",
                "Local audio/video file is empty.",
            )
        }
        return issues
    }
}

/** A single multipart form field. The file part carries its suggested filename. */
data class MultipartField(
    val name: String,
    val value: String,
    val fileName: String? = null,
)

/**
 * Immutable, network-free description of one `audio/transcriptions` POST.
 * [headers] may contain only the Authorization entry; the multipart Content-Type and
 * boundary are produced by the HTTP client and are intentionally not stored here.
 */
data class WhisperRequestSpec(
    val url: String,
    val httpMethod: String = "POST",
    val headers: List<Pair<String, String>>,
    val fields: List<MultipartField>,
) {
    /** Redacted description safe for logs/UI: no secret value, no full token. */
    fun redactedSummary(): String = buildString {
        append("POST ").append(url).append("; ")
        append("headers=[").append(headers.joinToString { (k, _) -> k }).append("]; ")
        append("fields=[").append(fields.joinToString { it.name }).append("]")
    }

    fun field(name: String): String? = fields.firstOrNull { it.name == name }?.value
}

/**
 * Pure builder for the OpenAI-compatible `audio/transcriptions` request shape.
 * URL normalization mirrors `WhisperSpeechToText.buildTranscriptionUrl` (kept here
 * without OkHttp/Android imports so the request contract is unit-testable).
 */
object WhisperRequestBuilder {
    const val FILE_FIELD = "file"
    const val MODEL_FIELD = "model"
    const val RESPONSE_FORMAT_FIELD = "response_format"
    const val LANGUAGE_FIELD = "language"
    const val DEFAULT_MODEL = "whisper-1"

    /**
     * Production response format: `verbose_json` (carries `segments[{start,end,text}]`).
     * The real media client ([WhisperSpeechToText]) requests exactly this and parses it
     * with `WhisperVerboseJsonParser` (media:processor); the feature-module
     * [WhisperResponseParser] covers the same shape. The request format and the parser
     * MUST stay in sync — `srt` is supported only as an explicit opt-in for callers
     * that bring their own SRT parser.
     */
    const val DEFAULT_RESPONSE_FORMAT = "verbose_json"

    /**
     * Normalizes a user-configured endpoint to the transcriptions URL:
     *  - already ends with `/audio/transcriptions` -> used as-is;
     *  - ends with `/v1` -> append `/audio/transcriptions`;
     *  - otherwise append `/v1/audio/transcriptions`.
     */
    fun normalizeUrl(endpoint: String): String {
        val e = endpoint.trim().trimEnd('/')
        return when {
            e.endsWith("/audio/transcriptions") -> e
            e.endsWith("/v1") -> "$e/audio/transcriptions"
            e.isEmpty() -> e
            else -> "$e/v1/audio/transcriptions"
        }
    }

    /**
     * Builds the request spec. [languageCode] blank/null omits the `language` field
     * entirely (server auto-detects). [responseFormat] selects `verbose_json` (default;
     * the production media client parses timed segments via `WhisperVerboseJsonParser`
     * in media:processor, and [WhisperResponseParser] covers the same shape here) or
     * `srt` (explicit opt-in only; caller must bring its own SRT parser). Unknown
     * formats fall back to `verbose_json`.
     */
    fun build(
        endpoint: String,
        model: String,
        secret: WhisperSecret,
        fileName: String,
        languageCode: String? = null,
        responseFormat: String = DEFAULT_RESPONSE_FORMAT,
    ): WhisperRequestSpec {
        val url = normalizeUrl(endpoint)
        val fields = mutableListOf(
            MultipartField(FILE_FIELD, "", fileName = fileName.ifBlank { "audio" }),
            MultipartField(MODEL_FIELD, model.ifBlank { DEFAULT_MODEL }),
            MultipartField(
                RESPONSE_FORMAT_FIELD,
                when {
                    responseFormat.equals("verbose_json", ignoreCase = true) -> "verbose_json"
                    responseFormat.equals("srt", ignoreCase = true) -> "srt"
                    else -> DEFAULT_RESPONSE_FORMAT
                },
            ),
        )
        val lang = languageCode?.trim().orEmpty()
        if (lang.isNotBlank()) {
            fields += MultipartField(LANGUAGE_FIELD, lang.take(2).lowercase())
        }
        val headers = listOfNotNull(secret.authorizationHeader())
        return WhisperRequestSpec(url = url, headers = headers, fields = fields)
    }
}
