package app.vidmatex.feature.subtitles.transcription

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Enqueue helper for [TranscriptionWorker]. Tag [TAG] (`vidmatex_transcription`) lets
 * the ViewModel observe all transcription jobs; the request id is derived
 * deterministically from `jobId` so a job can be cancelled by id without persisting
 * WorkManager UUIDs.
 *
 * Constraints: network connectivity always; `NetworkType.UNMETERED` (Wi-Fi) only when
 * the user's settings enable `requireWifi` (read by the caller before enqueue).
 * Exponential backoff, 30 s base interval, applied by WorkManager on transient retries.
 *
 * [dedupKey] is [TranscriptionDedup.keyFor] of the source path; the ViewModel keeps an
 * in-process active-key registry and the key also rides in worker data for observability.
 */
object TranscriptionWork {
    const val TAG = TranscriptionWorker.TAG

    /** Deterministic WorkManager id for a job, shared by enqueue and cancel. */
    fun workIdFor(jobId: String): UUID =
        UUID.nameUUIDFromBytes("vidmatex:transcription:$jobId".toByteArray(Charsets.UTF_8))

    /** Cancel (but do not delete) a previously enqueued job by caller job id. */
    fun cancel(context: Context, jobId: String) {
        if (jobId.isBlank()) return
        WorkManager.getInstance(context).cancelWorkById(workIdFor(jobId))
    }

    fun enqueue(
        context: Context,
        audioPath: String,
        sourceLang: String,
        jobId: String,
        requireWifi: Boolean,
        dedupKey: String = TranscriptionDedup.keyFor(audioPath),
    ): WorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (requireWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setId(workIdFor(jobId))
            .addTag(TAG)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30_000L, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    TranscriptionWorker.KEY_AUDIO_PATH to audioPath,
                    TranscriptionWorker.KEY_SOURCE_LANG to sourceLang,
                    TranscriptionWorker.KEY_JOB_ID to jobId,
                    TranscriptionWorker.KEY_DEDUP_KEY to dedupKey,
                )
            )
            .build()
        WorkManager.getInstance(context).enqueue(request)
        return request
    }
}
