package app.vidmatex.media.processor

import android.content.Context
import app.vidmatex.core.common.AppResult
import app.vidmatex.core.common.SettingsRepository
import app.vidmatex.core.model.AppSettings
import app.vidmatex.core.model.SubtitleCue
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * Pure SRT parser used by [WhisperSpeechToText]. Kept internal and dependency-free so it
 * is unit-testable without Android or OkHttp. Tolerates both comma (SRT) and dot (VTT)
 * fractional separators, since Whisper servers may return either.
 */
internal object WhisperSrtParser {
    private val TS_RE = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )

    fun parse(text: String): List<SubtitleCue> {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n").trimStart('\uFEFF')
        val blocks = normalized.split(Regex("""\n[ \t]*\n"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val cues = mutableListOf<SubtitleCue>()
        var nextIndex = 1
        for (block in blocks) {
            val lines = block.split("\n").map { it.trimEnd() }
            val tsIdx = lines.indexOfFirst { it.contains("-->") }
            if (tsIdx < 0) continue
            val m = TS_RE.find(lines[tsIdx].trim()) ?: continue
            val (h1, m1, s1, f1, h2, m2, s2, f2) = m.destructured
            val start = toMs(h1, m1, s1, f1)
            val end = toMs(h2, m2, s2, f2)
            val index = lines.subList(0, tsIdx)
                .firstOrNull { it.isNotBlank() }
                ?.trim()?.toIntOrNull() ?: nextIndex
            val textLines = lines.subList(tsIdx + 1, lines.size).filter { it.isNotBlank() }
            if (textLines.isEmpty()) continue
            cues.add(SubtitleCue(index, start, end, textLines))
            nextIndex = index + 1
        }
        return cues
    }

    private fun toMs(h: String, m: String, s: String, frac: String): Long {
        val ms = when (frac.length) {
            1 -> frac.toInt() * 100
            2 -> frac.toInt() * 10
            else -> frac.take(3).padEnd(3, '0').toInt()
        }
        return h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + ms
    }
}

/**
 * Pure endpoint-availability validation (no Android imports; JVM-testable).
 *
 * A configured Whisper endpoint counts as available when it is:
 *  - non-blank after trimming;
 *  - a syntactically parseable absolute URL with a host;
 *  - `https://` (production recommendation; key-bearing traffic is encrypted), or
 *  - `http://` restricted to loopback/development hosts (`localhost`, `127.0.0.1`,
 *    `::1`, `10.0.2.2` emulator host) so local self-hosted servers remain usable
 *    without TLS during development.
 *
 * Everything else (blank, malformed, missing host, `ftp`/`file`/`javascript`, or a
 * non-loopback plain-`http` host) is invalid and reports unavailable. The API key
 * itself is never required for availability: a valid endpoint with no key still
 * counts as available for providers that do not require auth.
 */
object WhisperEndpointValidator {
    private val LOOPBACK_HOSTS = setOf(
        "localhost",
        "127.0.0.1",
        "0.0.0.0",
        "::1",
        "[::1]",
        "10.0.2.2",
    )
    fun isValid(raw: String?): Boolean {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        if (scheme.isEmpty() || host.isEmpty()) return false
        return when (scheme) {
            "https" -> true
            "http" -> host in LOOPBACK_HOSTS || host.endsWith(".localhost") || host.endsWith(".local")
            else -> false
        }
    }
}

/**
 * Real API-based speech-to-text via a self-hosted OpenAI-compatible Whisper endpoint
 * (e.g. faster-whisper-server, whisper.cpp server, or OpenAI's own API). Constructed
 * per-call by [SettingsAwareSpeechToText] from current user settings; no endpoint or
 * API key is ever hardcoded in the APK, and the key is never logged.
 *
 * Progress: set [onProgress] to receive 0..100 percent updates. The [SpeechToText]
 * interface is unchanged (per CONTRACTS §3); callers that need progress cast the
 * concrete binding ([SettingsAwareSpeechToText] or this class) and set the listener.
 *
 * Large files (> [MAX_SINGLE_UPLOAD_BYTES]) are split into ~[CHUNK_DURATION_SEC]-second
 * chunks with FFmpegKit (re-encoded to 16 kHz mono MP3), transcribed individually, and
 * cue timestamps are offset by chunk start. If FFmpegKit is unavailable or splitting
 * fails, the whole file is uploaded and any server rejection is surfaced clearly.
 *
 * The split runs on the maintained FFmpegKit GPL fork
 * (`dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9`; same `com.arthenica.ffmpegkit.*`
 * Java API as the retired Arthenica release) through
 * `executeWithArguments(String[])`. The chunk vector below is a fixed `List<String>`
 * allowlist plus app-controlled paths — it is never a shell string.
 */
class WhisperSpeechToText(
    private val endpoint: String,
    private val apiKey: String?,
    private val okHttpClient: OkHttpClient,
    private val cacheDir: File,
) : SpeechToText {

    /** Progress callback (0..100). Null by default. */
    var onProgress: ((percent: Int) -> Unit)? = null

    /** Availability mirrors [WhisperEndpointValidator]: scheme/host rules, not merely non-blank. */
    override fun isAvailable(): Boolean = WhisperEndpointValidator.isValid(endpoint)

    override suspend fun transcribe(audioPath: String, sourceLang: String?): AppResult<List<SubtitleCue>> {
        if (!isAvailable()) {
            return AppResult.Error(
                "Whisper endpoint not configured. Set a self-hosted Whisper endpoint in Settings to enable speech-to-text.",
                recoverable = true,
            )
        }
        return try {
            transcribeInternal(audioPath, sourceLang)
        } catch (e: Exception) {
            AppResult.Error("Transcription failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun transcribeInternal(audioPath: String, sourceLang: String?): AppResult<List<SubtitleCue>> {
        val file = File(audioPath)
        if (!file.exists() || !file.isFile) {
            return AppResult.Error("Audio file not found: $audioPath")
        }
        onProgress?.invoke(0)

        val chunks = try {
            splitIfNeeded(file)
        } catch (t: Throwable) {
            // FFmpegKit missing / native load failure -> fall back to single upload.
            listOf(Chunk(file, 0L, ownsFile = false))
        }

        // Per-chunk cue lists and their source-timeline offsets, merged after the loop
        // with [WhisperChunkMerger] so every segment carries its original timestamp
        // shifted by the chunk's nominal start. verbose_json is requested per chunk
        // (see [transcribeChunk]) and parsed by [WhisperVerboseJsonParser] — the request
        // format and the parser are the same format by construction.
        val chunkCues = ArrayList<List<SubtitleCue>>(chunks.size)
        val chunkOffsets = ArrayList<Long>(chunks.size)
        try {
            for ((i, chunk) in chunks.withIndex()) {
                when (val jsonResult = transcribeChunk(chunk.file, sourceLang)) {
                    is AppResult.Error -> return jsonResult
                    is AppResult.Success -> {
                        when (val parsed = WhisperVerboseJsonParser.parse(jsonResult.value)) {
                            is AppResult.Error -> return parsed
                            is AppResult.Success -> {
                                if (parsed.value.isEmpty()) {
                                    return AppResult.Error("Transcription server returned no recognizable cues.")
                                }
                                chunkCues += parsed.value
                                chunkOffsets += chunk.offsetMs
                            }
                        }
                    }
                }
                onProgress?.invoke(((i + 1) * 100 / chunks.size).coerceAtMost(100))
            }
        } finally {
            // Clean up only chunk files we created (not the original audio file).
            chunks.filter { it.ownsFile }.forEach { runCatching { it.file.delete() } }
            runCatching { chunks.firstOrNull { it.ownsFile }?.file?.parentFile?.delete() }
        }

        // Merge chunk cue lists on the source timeline: each chunk's segments are
        // offset by the chunk's nominal start, then the union is sorted, clamped and
        // re-indexed 1..n (WhisperChunkMerger). Original segment timestamps are
        // preserved within each chunk; only the chunk-level offset is applied.
        val merged = WhisperChunkMerger.merge(chunkCues, chunkOffsets)
        onProgress?.invoke(100)
        return AppResult.Success(merged)
    }

    // ---------------------------------------------------------------- chunking

    private data class Chunk(val file: File, val offsetMs: Long, val ownsFile: Boolean = false)

    private suspend fun splitIfNeeded(file: File): List<Chunk> {
        if (file.length() <= MAX_SINGLE_UPLOAD_BYTES) return listOf(Chunk(file, 0L, ownsFile = false))
        val chunkDir = File(cacheDir, "whisper_chunks_${file.name.hashCode()}_${System.currentTimeMillis()}").apply { mkdirs() }
        val pattern = File(chunkDir, "chunk_%03d.mp3").absolutePath
        // Re-encode to 16 kHz mono MP3 (Whisper-friendly) and split on fixed duration.
        // Tokens come from the pure [WhisperChunkVector] (identical allowlist + app paths).
        val args = WhisperChunkVector.build(file.absolutePath, pattern, CHUNK_DURATION_SEC)
        val returnCode = withContext(Dispatchers.IO) {
            // Maintained fork keeps com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments;
            // argument vector (List<String>) unchanged from the retired Arthenica build.
            FFmpegKit.executeWithArguments(args.toTypedArray()).returnCode
        }
        if (!ReturnCode.isSuccess(returnCode)) {
            // Split failed — attempt single upload; server will reject if too large.
            return listOf(Chunk(file, 0L, ownsFile = false))
        }
        val chunkFiles = chunkDir.listFiles { f -> f.name.startsWith("chunk_") && f.name.endsWith(".mp3") }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (chunkFiles.isEmpty()) return listOf(Chunk(file, 0L, ownsFile = false))
        return chunkFiles.mapIndexed { i, f ->
            Chunk(f, offsetMs = i * CHUNK_DURATION_SEC * 1000L, ownsFile = true)
        }
    }

    // ---------------------------------------------------------------- HTTP

    private suspend fun transcribeChunk(file: File, sourceLang: String?): AppResult<String> {
        val url = buildTranscriptionUrl(endpoint)
        val mediaType = if (file.extension.equals("mp3", ignoreCase = true)) "audio/mpeg" else "application/octet-stream"
        val requestBody = file.asRequestBody(mediaType.toMediaType())
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, requestBody)
            .addFormDataPart("model", "whisper-1")
            // verbose_json: the response carries segments[{start,end,text}] which
            // [WhisperVerboseJsonParser] consumes. MUST match the parser — never
            // request `srt` while the JSON segment parser is wired in.
            .addFormDataPart("response_format", "verbose_json")
            .apply { if (!sourceLang.isNullOrBlank()) addFormDataPart("language", sourceLang.trim()) }
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .apply { if (!apiKey.isNullOrBlank()) addHeader("Authorization", "Bearer $apiKey") }
            .build()
        return withContext(Dispatchers.IO) {
            try {
                okHttpClient.newCall(request).execute().use { resp ->
                    val respBody = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        // Never include the apiKey or full auth header in the message.
                        val snippet = respBody.replace(Regex("""[A-Za-z0-9_\-]{20,}"""), "<redacted>").take(200)
                        AppResult.Error("Transcription server returned HTTP ${resp.code}: $snippet")
                    } else if (respBody.isBlank()) {
                        AppResult.Error("Transcription server returned an empty response.")
                    } else {
                        AppResult.Success(respBody)
                    }
                }
            } catch (e: SocketTimeoutException) {
                AppResult.Error("Transcription request timed out. Check your connection.", recoverable = true)
            } catch (e: UnknownHostException) {
                AppResult.Error("Could not reach the transcription server. Check the endpoint URL and your network.", recoverable = true)
            } catch (e: java.io.IOException) {
                AppResult.Error("Network error during transcription: ${e.message ?: e.javaClass.simpleName}", recoverable = true)
            } catch (e: Exception) {
                AppResult.Error("Transcription failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        /** OpenAI's hard limit is 25 MB; stay slightly below. */
        const val MAX_SINGLE_UPLOAD_BYTES = 24L * 1024 * 1024
        const val CHUNK_DURATION_SEC = 600L // 10 minutes per chunk

        /**
         * Normalizes the user-configured endpoint to the full transcriptions URL.
         * If the endpoint already includes the path (…/v1/audio/transcriptions), use
         * it as-is; if it ends at …/v1, append /audio/transcriptions; otherwise
         * append /v1/audio/transcriptions.
         */
        fun buildTranscriptionUrl(endpoint: String): String {
            val e = endpoint.trim().trimEnd('/')
            return when {
                e.endsWith("/audio/transcriptions") -> e
                e.endsWith("/v1") -> "$e/audio/transcriptions"
                else -> "$e/v1/audio/transcriptions"
            }
        }
    }
}

/**
 * Singleton [SpeechToText] binding. Reads current [AppSettings] at transcribe time:
 * if `whisperEndpoint` is blank, delegates to [NoOpSpeechToText] (clear recoverable
 * message); otherwise constructs a per-call [WhisperSpeechToText] with the configured
 * endpoint/key and delegates. Progress is forwarded — set [onProgress] on this
 * instance (cast the injected [SpeechToText] to [SettingsAwareSpeechToText]).
 */
@Singleton
class SettingsAwareSpeechToText @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context,
) : SpeechToText {

    /** Forwarded to the per-call Whisper instance. */
    var onProgress: ((percent: Int) -> Unit)? = null

    @Volatile
    private var lastEndpointSnapshot: String = ""

    /**
     * True only when a valid configured endpoint exists: non-blank, parseable URL with
     * a host, and an https scheme (or http restricted to loopback development hosts).
     * False for blank, malformed, host-less, or plain-http non-loopback endpoints.
     * The `SpeechToText` interface signature is unchanged.
     */
    override fun isAvailable(): Boolean = WhisperEndpointValidator.isValid(lastEndpointSnapshot)

    /**
     * Additive concrete-class hook (not part of the [SpeechToText] interface): refresh
     * the availability snapshot from a fresh settings emission. Safe to call on the
     * main thread; it only trims and validates a string.
     */
    fun syncWithSettings(settings: AppSettings) {
        lastEndpointSnapshot = settings.whisperEndpoint.trim()
    }

    override suspend fun transcribe(audioPath: String, sourceLang: String?): AppResult<List<SubtitleCue>> {
        val settings = settingsRepository.current()
        val endpoint = settings.whisperEndpoint.trim()
        lastEndpointSnapshot = endpoint
        if (!WhisperEndpointValidator.isValid(endpoint)) {
            // Blank endpoint -> recoverable NoOp message (unchanged behavior). A
            // present-but-invalid endpoint uses the same clear recoverable error; no
            // request is ever sent to an unvalidated scheme or host.
            return NoOpSpeechToText().transcribe(audioPath, sourceLang)
        }
        val whisper = WhisperSpeechToText(
            endpoint = endpoint,
            apiKey = settings.whisperApiKey.trim().ifBlank { null },
            okHttpClient = okHttpClient,
            cacheDir = context.cacheDir,
        )
        whisper.onProgress = onProgress
        return whisper.transcribe(audioPath, sourceLang)
    }
}

/**
 * Default fallback [SpeechToText]. Never transcribes; surfaces a clear message so the
 * user knows STT is unavailable until they configure a Whisper endpoint (or an on-device
 * model in a future release). Used by [SettingsAwareSpeechToText] when no endpoint is set.
 */
class NoOpSpeechToText @Inject constructor() : SpeechToText {
    override fun isAvailable(): Boolean = false
    override suspend fun transcribe(audioPath: String, sourceLang: String?): AppResult<List<SubtitleCue>> =
        AppResult.Error(
            "Speech-to-text is not configured. Set a self-hosted Whisper endpoint in Settings to enable it.",
            recoverable = true,
        )
}
