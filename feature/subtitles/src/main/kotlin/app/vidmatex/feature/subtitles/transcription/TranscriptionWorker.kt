package app.vidmatex.feature.subtitles.transcription

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.vidmatex.core.common.AppResult
import app.vidmatex.core.common.DispatcherProvider
import app.vidmatex.core.common.SettingsRepository
import app.vidmatex.core.database.PendingTranscriptionDao
import app.vidmatex.core.model.SubtitleCue
import app.vidmatex.core.model.SubtitleFormat
import app.vidmatex.media.processor.SettingsAwareSpeechToText
import app.vidmatex.media.processor.SpeechToText
import app.vidmatex.media.processor.SubtitleParser
import app.vidmatex.feature.subtitles.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WorkManager transcription worker. Feeds a local audio/video file through the injected
 * [SpeechToText] (real `WhisperSpeechToText` when a valid endpoint is configured) and,
 * on success, writes BOTH an `.srt` and a `.vtt` file under
 * `filesDir/transcriptions/<jobId>/` using the project [SubtitleParser].
 *
 * Input data keys are private constants here (not `core/common/Constants.kt`):
 *  - [KEY_AUDIO_PATH] absolute path to the local audio/video file
 *  - [KEY_SOURCE_LANG] spoken ISO code; `""`/blank = auto-detect (language field omitted)
 *  - [KEY_JOB_ID] caller-generated job id; output files are namespaced by it
 *
 * Progress: [PROGRESS_KEY] 0..100 int plus [STAGE_KEY] string (`preparing`,
 * `transcribing`, `writing`), forwarded from the STT progress callback.
 *
 * Output data (success): [OUT_SRT_PATH], [OUT_VTT_PATH], [OUT_CUE_COUNT].
 * Output data (failure): [OUT_ERROR_MESSAGE] (friendly), [OUT_ERROR_KIND], [OUT_HTTP_CODE].
 *
 * Retry policy (see [TranscriptionRetryPolicy]): transient network/timeout/429/5xx
 * errors retry with WorkManager exponential backoff (30 s base; configured in
 * [TranscriptionWork]) for at most [TranscriptionRetryPolicy.MAX_TRANSIENT_RETRIES]
 * attempts; bad files, 4xx auth errors, empty responses and zero-cue results are
 * permanent `Result.failure()`.
 */
@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val speechToText: SpeechToText,
    private val parser: SubtitleParser,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val pendingTranscriptionDao: PendingTranscriptionDao,
) : CoroutineWorker(context, params) {

    /**
     * The STT progress callback is a non-suspend lambda, while [setProgress] is
     * suspend; forward updates on this IO scope and cancel it when the worker stops.
     */
    private val progressScope = CoroutineScope(dispatchers.io + SupervisorJob())

    companion object {
        const val TAG = "vidmatex_transcription"

        // ---- input data keys (private to this worker; do not edit core Constants) ----
        const val KEY_AUDIO_PATH = "audio_path"
        const val KEY_SOURCE_LANG = "source_lang"
        const val KEY_JOB_ID = "job_id"
        /** Dedup slot = [TranscriptionDedup.keyFor] of the source path (one active job/file). */
        const val KEY_DEDUP_KEY = "dedup_key"

        // ---- progress data keys ----
        const val PROGRESS_KEY = "progress"
        const val STAGE_KEY = "stage"

        // ---- success output keys ----
        const val OUT_SRT_PATH = "srt_path"
        const val OUT_VTT_PATH = "vtt_path"
        const val OUT_CUE_COUNT = "cue_count"
        const val OUT_DEDUP_KEY = "out_dedup_key"

        // ---- failure output keys ----
        const val OUT_ERROR_MESSAGE = "error_message"
        const val OUT_ERROR_KIND = "error_kind"
        const val OUT_HTTP_CODE = "http_code"

        internal const val STAGE_PREPARING = "preparing"
        internal const val STAGE_TRANSCRIBING = "transcribing"
        internal const val STAGE_WRITING = "writing"
    }

    override suspend fun doWork(): Result {
        // CoroutineWorker.onStopped is final and cannot be overridden; cleanup on
        // stop/cancel is performed in the finally block below (isStopped is true only
        // when the worker was stopped, not on normal success/failure).
        try {
        val audioPath = inputData.getString(KEY_AUDIO_PATH).orEmpty()
        val sourceLang = inputData.getString(KEY_SOURCE_LANG).orEmpty()
        val jobId = inputData.getString(KEY_JOB_ID).orEmpty()
        if (audioPath.isBlank() || jobId.isBlank()) {
            return permanentFailure(
                TranscriptionRetryPolicy.Kind.BAD_FILE,
                applicationContext.getString(R.string.subtitles_transcribe_bad_file),
            )
        }
        val audioFile = File(audioPath)
        if (!audioFile.exists() || !audioFile.isFile || audioFile.length() == 0L) {
            return permanentFailure(
                TranscriptionRetryPolicy.Kind.BAD_FILE,
                applicationContext.getString(R.string.subtitles_transcribe_bad_file),
            )
        }

        // Re-validate at run time: endpoint may have been removed after enqueue.
        val settings = settingsRepository.current()
        // Pre-flight validation before any request: endpoint URL rules, model slot,
        // key presence, and file state. The API key stays in memory only
        // ([WhisperSecret]) and never enters worker data or error messages; a missing
        // key is a WARNING because keyless self-hosted endpoints are valid, while an
        // invalid endpoint/model or bad file is a permanent failure.
        val config = WhisperEndpointConfig(
            endpoint = settings.whisperEndpoint.trim(),
            model = WhisperRequestBuilder.DEFAULT_MODEL,
            secret = WhisperSecret.from(settings.whisperApiKey),
        )
        val blockingIssues = config
            .validate(audioFile.exists(), audioFile.length())
            .filter { it.severity == ConfigSeverity.BLOCKING }
        if (blockingIssues.any { it.code == "invalid_endpoint" || it.code == "missing_model" }) {
            return permanentFailure(
                TranscriptionRetryPolicy.Kind.UNCONFIGURED,
                applicationContext.getString(R.string.subtitles_transcribe_unconfigured),
            )
        }
        if (blockingIssues.any { it.code == "missing_file" || it.code == "empty_file" }) {
            return permanentFailure(
                TranscriptionRetryPolicy.Kind.BAD_FILE,
                applicationContext.getString(R.string.subtitles_transcribe_bad_file),
            )
        }
        // Orchestration chunk manifest. The SpeechToText interface exposes no duration
        // today, so the planner queues one segment; native FFmpegKit splitting (fixed
        // 600 s segments, overlap, timestamp offsets) stays in the media client and is
        // device-gated — never faked here.
        @Suppress("UNUSED_VARIABLE")
        val manifest = TranscriptionChunkPlanner.plan(durationMs = null)
        // Unknown source duration: the manifest contains one queue segment. Native
        // FFmpeg splitting (fixed segments + overlap + offsets) runs in media:processor
        // on device; this static build cannot execute it, so no chunked output is faked.
        // Manifest is always non-empty by planner contract; native splitting runs in
        // media:processor on device. See TranscriptionChunkPlanner for the window math.
        Unit

        setProgress(workDataOf(PROGRESS_KEY to 0, STAGE_KEY to STAGE_PREPARING))

        // Forward STT progress (0..100) into WorkManager progress data. setProgress
        // is suspend, so updates are launched on the worker's IO progress scope.
        (speechToText as? SettingsAwareSpeechToText)?.onProgress = { pct ->
            progressScope.launch {
                setProgress(
                    workDataOf(
                        PROGRESS_KEY to pct.coerceIn(0, 100),
                        STAGE_KEY to STAGE_TRANSCRIBING,
                    )
                )
            }
        }

        val result = try {
            speechToText.transcribe(audioPath, sourceLang.ifBlank { null })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val kind = TranscriptionRetryPolicy.classifyThrowable(e)
            return retryOrFail(kind, e.message, TranscriptionRetryPolicy.httpCode(e.message.orEmpty()))
        }

        return when (result) {
            is AppResult.Success -> {
                val cues = result.value
                // Enforce the non-empty cue contract even if a future implementation
                // returns an empty success list: an empty transcript is a permanent failure.
                if (cues.isEmpty()) {
                    return permanentFailure(
                        TranscriptionRetryPolicy.Kind.NO_CUES,
                        applicationContext.getString(R.string.subtitles_transcribe_empty_error),
                    )
                }
                writeOutputs(jobId, cues)
            }
            is AppResult.Error -> {
                val httpCode = TranscriptionRetryPolicy.httpCode(result.message)
                val kind = TranscriptionRetryPolicy.classify(
                    result.message,
                    httpCode = httpCode,
                    recoverableFlag = result.recoverable,
                )
                retryOrFail(kind, result.message, httpCode)
            }
        }
        } finally {
            progressScope.cancel("Transcription worker finished or stopped")
            // Cancellation cleanup: remove partial sidecars written for this job only.
            // The cached input copy is retained so the user can retry without re-picking.
            if (isStopped) {
                cleanupJobOutputs()
                // Durable queue: cancelled job → reset ENQUEUED→PENDING so the next app
                // start re-enqueues (the WorkManager job is gone on cancel). NonCancellable
                // because the worker coroutine is already cancelled at this point.
                withContext(NonCancellable) { resetPendingForRetry() }
            }
        }
    }

    /** Best-effort delete of this job's output directory under filesDir/transcriptions. */
    private fun cleanupJobOutputs() {
        val jobId = inputData.getString(KEY_JOB_ID).orEmpty()
        if (jobId.isBlank()) return
        runCatching {
            val dir = File(TranscriptionPaths.outputDir(applicationContext.filesDir.absolutePath, jobId))
            dir.listFiles()?.forEach { runCatching { it.delete() } }
            runCatching { dir.delete() }
        }
    }

    /** Writes SRT + VTT sidecars; success data carries both paths and the cue count. */
    private suspend fun writeOutputs(jobId: String, cues: List<SubtitleCue>): Result =
        withContext(dispatchers.io) {
            setProgress(workDataOf(PROGRESS_KEY to 95, STAGE_KEY to STAGE_WRITING))
            val dirPath = TranscriptionPaths.outputDir(applicationContext.filesDir.absolutePath, jobId)
            val dir = File(dirPath)
            if (!dir.exists() && !dir.mkdirs()) {
                return@withContext retryOrFail(
                    TranscriptionRetryPolicy.Kind.BAD_FILE,
                    "Could not create transcription output directory.",
                    null,
                )
            }
            val srt = File(TranscriptionPaths.srtFile(dirPath, jobId))
            val vtt = File(TranscriptionPaths.vttFile(dirPath, jobId))
            // Defensive monotonic ordering: sorted by start time, re-indexed 1..n;
            // timestamps and line text are otherwise untouched.
            val ordered = CueOrdering.ensureMonotonic(cues)
            try {
                // SRT carries a UTF-8 BOM so legacy players detect the encoding; VTT
                // must begin with "WEBVTT" and therefore receives no BOM.
                srt.writeText("\uFEFF" + parser.write(SubtitleFormat.SRT, ordered), Charsets.UTF_8)
                vtt.writeText(parser.write(SubtitleFormat.VTT, ordered), Charsets.UTF_8)
            } catch (e: Exception) {
                val kind = TranscriptionRetryPolicy.classifyThrowable(e)
                return@withContext retryOrFail(kind, e.message ?: "write error", null)
            }
            // Durable queue: mark SUCCEEDED so the pending row is never re-enqueued.
            markPendingSucceeded()
            Result.success(
                workDataOf(
                    OUT_SRT_PATH to srt.absolutePath,
                    OUT_VTT_PATH to vtt.absolutePath,
                    OUT_CUE_COUNT to ordered.size,
                    OUT_DEDUP_KEY to inputData.getString(KEY_DEDUP_KEY).orEmpty(),
                )
            )
        }

    private suspend fun retryOrFail(
        kind: TranscriptionRetryPolicy.Kind,
        rawMessage: String?,
        httpCode: Int?,
    ): Result {
        if (TranscriptionRetryPolicy.shouldRetry(kind) &&
            !TranscriptionRetryPolicy.retryExhausted(runAttemptCount)
        ) {
            return Result.retry()
        }
        val friendly = friendlyMessage(kind, rawMessage, httpCode)
        // Durable queue: terminal failure (retries exhausted / permanent) → mark FAILED
        // so the pending row is never re-enqueued. Retryable failures leave the row
        // ENQUEUED (WorkManager retries; the row stays ENQUEUED until terminal).
        markPendingFailed(friendly)
        return Result.failure(
            workDataOf(
                OUT_ERROR_MESSAGE to friendly,
                OUT_ERROR_KIND to kind.name,
                OUT_HTTP_CODE to (httpCode ?: -1),
            )
        )
    }

    private suspend fun permanentFailure(kind: TranscriptionRetryPolicy.Kind, friendly: String): Result {
        markPendingFailed(friendly)
        return Result.failure(
            workDataOf(
                OUT_ERROR_MESSAGE to friendly,
                OUT_ERROR_KIND to kind.name,
                OUT_HTTP_CODE to -1,
            )
        )
    }

    // ---- durable pending-transcription status marking (best-effort, never masks result) ----

    /** Mark the pending row SUCCEEDED (terminal). No-op for manually enqueued jobs (blank dedupKey). */
    private suspend fun markPendingSucceeded() {
        val dedupKey = inputData.getString(KEY_DEDUP_KEY).orEmpty()
        if (dedupKey.isBlank()) return
        runCatching { pendingTranscriptionDao.markSucceeded(dedupKey, System.currentTimeMillis()) }
    }

    /** Mark the pending row FAILED (terminal) with a truncated error. */
    private suspend fun markPendingFailed(friendly: String) {
        val dedupKey = inputData.getString(KEY_DEDUP_KEY).orEmpty()
        if (dedupKey.isBlank()) return
        runCatching {
            pendingTranscriptionDao.markFailed(dedupKey, friendly.take(200), System.currentTimeMillis())
        }
    }

    /**
     * On worker cancellation/stop: reset ENQUEUED→PENDING so the next app-start scan
     * re-enqueues. Guarded by `status = 'ENQUEUED'` in the DAO so a terminal row is never
     * revived. Runs under NonCancellable because the worker coroutine is cancelled.
     */
    private suspend fun resetPendingForRetry() {
        val dedupKey = inputData.getString(KEY_DEDUP_KEY).orEmpty()
        if (dedupKey.isBlank()) return
        runCatching { pendingTranscriptionDao.resetToPending(dedupKey, System.currentTimeMillis()) }
    }

    /** Maps a classified kind to a friendly, non-sensitive user-facing message. */
    private fun friendlyMessage(kind: TranscriptionRetryPolicy.Kind, raw: String?, httpCode: Int?): String =
        when (kind) {
            TranscriptionRetryPolicy.Kind.NETWORK, TranscriptionRetryPolicy.Kind.TIMEOUT ->
                applicationContext.getString(R.string.subtitles_transcribe_network_error)
            TranscriptionRetryPolicy.Kind.RATE_LIMIT, TranscriptionRetryPolicy.Kind.SERVER_5XX ->
                applicationContext.getString(
                    R.string.subtitles_transcribe_server_error,
                    (httpCode ?: 500).toString(),
                )
            TranscriptionRetryPolicy.Kind.PERMANENT_4XX ->
                if (httpCode == 401 || httpCode == 403) {
                    applicationContext.getString(
                        R.string.subtitles_transcribe_auth_error,
                        (httpCode ?: 401).toString(),
                    )
                } else {
                    applicationContext.getString(
                        R.string.subtitles_transcribe_server_error,
                        (httpCode ?: 400).toString(),
                    )
                }
            TranscriptionRetryPolicy.Kind.BAD_FILE ->
                applicationContext.getString(R.string.subtitles_transcribe_bad_file)
            TranscriptionRetryPolicy.Kind.EMPTY_RESPONSE ->
                applicationContext.getString(R.string.subtitles_transcribe_empty_error)
            TranscriptionRetryPolicy.Kind.NO_CUES ->
                applicationContext.getString(R.string.subtitles_transcribe_unsupported)
            TranscriptionRetryPolicy.Kind.UNCONFIGURED ->
                applicationContext.getString(R.string.subtitles_transcribe_unconfigured)
            TranscriptionRetryPolicy.Kind.UNKNOWN ->
                raw?.take(160)?.takeIf { it.isNotBlank() }
                    ?: applicationContext.getString(R.string.subtitles_transcribe_unsupported)
        }
}
