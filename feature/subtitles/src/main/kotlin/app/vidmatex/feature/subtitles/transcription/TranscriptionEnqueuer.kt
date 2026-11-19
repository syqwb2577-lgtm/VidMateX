package app.vidmatex.feature.subtitles.transcription

import android.content.Context
import app.vidmatex.media.processor.WhisperEndpointValidator
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * PUBLIC enqueue API for post-download auto-transcription. The `app` module (and any
 * caller) calls [TranscriptionEnqueuer.enqueue] after a download completes to start a
 * Whisper transcription of the local media file. Manual file selection in the
 * Subtitles screen remains a separate path ([TranscriptionWork.enqueue]); this object is
 * the single, documented integration point for programmatic enqueue.
 *
 * Hard guarantees:
 *  - If the Whisper endpoint is missing/invalid or the model is blank, NO work is
 *    enqueued and [EnqueueOutcome.NotConfigured] lists the exact required config.
 *    Transcription is never simulated.
 *  - Duplicate jobs for the same local file are suppressed via `enqueueUniqueWork`
 *    with [ExistingWorkPolicy.KEEP]; the unique name is derived from
 *    [TranscriptionDedup.keyFor] of the normalized path.
 *  - The API key never enters WorkManager input data, progress, or output data. It is
 *    read at worker run time from [SettingsRepository] (decrypted in memory only).
 *
 * The pure planning/validation logic lives in [TranscriptionEnqueuePlanner] so it is
 * unit-testable on the plain JVM without WorkManager/Android.
 */
object TranscriptionEnqueuer {
    /** Prefix for every unique WorkManager transcription job name. */
    const val UNIQUE_WORK_NAME_PREFIX = "vidmatex:transcription:"

    /**
     * Enqueue a transcription job for a local media file. Returns a detailed outcome;
     * callers that only need the request can use [enqueueOrNull].
     */
    fun enqueue(context: Context, config: TranscriptionEnqueueConfig): EnqueueOutcome {
        val plan = TranscriptionEnqueuePlanner.plan(config)
        return when (plan) {
            is EnqueuePlan.Ready -> {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(if (config.requireWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
                val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                    .setId(plan.workId)
                    .addTag(TranscriptionWork.TAG)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30_000L, TimeUnit.MILLISECONDS)
                    .setInputData(
                        workDataOf(
                            TranscriptionWorker.KEY_AUDIO_PATH to plan.audioPath,
                            TranscriptionWorker.KEY_SOURCE_LANG to plan.sourceLang,
                            TranscriptionWorker.KEY_JOB_ID to plan.jobId,
                            TranscriptionWorker.KEY_DEDUP_KEY to plan.dedupKey,
                        ),
                    )
                    .build()
                // KEEP: a second enqueue for the same file is a no-op while the first
                // runs; the caller observes via the TAG flow. REPLACE would cancel an
                // in-progress transcription and is intentionally not offered.
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(plan.uniqueWorkName, ExistingWorkPolicy.KEEP, request)
                EnqueueOutcome.Enqueued(
                    workRequest = request,
                    uniqueWorkName = plan.uniqueWorkName,
                    jobId = plan.jobId,
                    dedupKey = plan.dedupKey,
                )
            }
            is EnqueuePlan.NotConfigured -> EnqueueOutcome.NotConfigured(plan.missing)
            is EnqueuePlan.InvalidInput -> EnqueueOutcome.InvalidInput(plan.reason)
        }
    }

    /** Convenience: returns the [WorkRequest] only when enqueue actually happened. */
    fun enqueueOrNull(context: Context, config: TranscriptionEnqueueConfig): WorkRequest? =
        (enqueue(context, config) as? EnqueueOutcome.Enqueued)?.workRequest

    /** Cancel (but do not delete outputs) a previously enqueued job by unique name. */
    fun cancelUnique(context: Context, uniqueWorkName: String) {
        if (uniqueWorkName.isBlank()) return
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName)
    }
}

/**
 * Caller-supplied configuration for one enqueue. [whisperEndpoint] and [model] are
 * validated before enqueue; [whisperApiKey] is NOT stored here — it is read by the
 * worker at run time from secure settings, so it never crosses the enqueue boundary.
 */
data class TranscriptionEnqueueConfig(
    val localMediaPath: String,
    val whisperEndpoint: String,
    val model: String = WhisperRequestBuilder.DEFAULT_MODEL,
    /** Spoken ISO-639-1 code; blank/null = server auto-detect (language field omitted). */
    val sourceLang: String? = null,
    val requireWifi: Boolean = false,
    /** Caller job id; auto-derived from the path when blank. */
    val jobId: String? = null,
)

/** Detailed outcome of one enqueue attempt. */
sealed class EnqueueOutcome {
    /** Work was enqueued (or kept as already-running under KEEP policy). */
    data class Enqueued(
        val workRequest: WorkRequest,
        val uniqueWorkName: String,
        val jobId: String,
        val dedupKey: String,
    ) : EnqueueOutcome()

    /**
     * Enqueue deliberately skipped: required config is missing. [missing] lists the
     * exact keys the caller must provide (human-readable, never the key value).
     */
    data class NotConfigured(val missing: List<String>) : EnqueueOutcome()

    /** Enqueue skipped: the input (path/file) is invalid. */
    data class InvalidInput(val reason: String) : EnqueueOutcome()
}

/** Pure (no Android/WorkManager) planning result. */
sealed class EnqueuePlan {
    data class Ready(
        val audioPath: String,
        val sourceLang: String,
        val jobId: String,
        val dedupKey: String,
        val uniqueWorkName: String,
        val workId: UUID,
    ) : EnqueuePlan()

    data class NotConfigured(val missing: List<String>) : EnqueuePlan()
    data class InvalidInput(val reason: String) : EnqueuePlan()
}

/**
 * Pure pre-flight planner: validates config, derives the deterministic job id, dedup
 * key, unique WorkManager name, and WorkManager UUID. No WorkManager/Context imports,
 * so it is JVM-unit-testable. The actual enqueue is in [TranscriptionEnqueuer].
 */
object TranscriptionEnqueuePlanner {
    /** Required-config labels surfaced to the caller (never secret values). */
    const val MISSING_ENDPOINT = "whisperEndpoint (valid https URL, or loopback http for dev)"
    const val MISSING_MODEL = "model (non-blank Whisper model name, e.g. whisper-1)"

    fun plan(config: TranscriptionEnqueueConfig): EnqueuePlan {
        val missing = mutableListOf<String>()
        if (!WhisperEndpointValidator.isValid(config.whisperEndpoint)) {
            missing += MISSING_ENDPOINT
        }
        if (config.model.isBlank()) {
            missing += MISSING_MODEL
        }
        if (missing.isNotEmpty()) {
            return EnqueuePlan.NotConfigured(missing.toList())
        }
        val path = config.localMediaPath.trim()
        if (path.isEmpty()) {
            return EnqueuePlan.InvalidInput("localMediaPath is blank.")
        }
        val dedupKey = TranscriptionDedup.keyFor(path)
        val jobId = config.jobId?.trim()?.ifBlank { null } ?: defaultJobId(dedupKey)
        val uniqueWorkName = TranscriptionEnqueuer.UNIQUE_WORK_NAME_PREFIX + dedupKey
        val workId = TranscriptionWork.workIdFor(jobId)
        val sourceLang = config.sourceLang?.trim().orEmpty()
        return EnqueuePlan.Ready(
            audioPath = path,
            sourceLang = sourceLang,
            jobId = jobId,
            dedupKey = dedupKey,
            uniqueWorkName = uniqueWorkName,
            workId = workId,
        )
    }

    /** Deterministic job id from the dedup key (stable across enqueues of the same file). */
    fun defaultJobId(dedupKey: String): String = "auto_${dedupKey.take(16)}"
}
