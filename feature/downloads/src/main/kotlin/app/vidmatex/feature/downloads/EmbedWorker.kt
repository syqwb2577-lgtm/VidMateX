package app.vidmatex.feature.downloads

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.vidmatex.core.common.AppResult
import app.vidmatex.core.common.Constants
import app.vidmatex.core.common.SettingsRepository
import app.vidmatex.core.database.DownloadDao
import app.vidmatex.core.database.PendingTranscriptionDao
import app.vidmatex.core.model.DownloadState
import app.vidmatex.core.model.EmbedMode
import app.vidmatex.media.processor.FFmpegProcessor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import app.vidmatex.core.model.DownloadSpec

/**
 * Runs after [DownloadWorker] when soft/hard subtitle embedding is requested. Reads the
 * temp video + subtitle paths stored on the entity, invokes the allowlisted FFmpeg
 * processor, then finalizes the output to MediaStore/SAF and marks the job COMPLETED.
 *
 * Retry policy (contract task 5): transient processor/network errors retry with
 * WorkManager backoff, but embedding failures are capped at [RetryPolicy.MAX_EMBED_RETRIES]
 * retries; missing inputs / permanent failures return `Result.failure()` immediately.
 */
@HiltWorker
class EmbedWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: DownloadDao,
    private val ffmpegProcessor: FFmpegProcessor,
    private val settingsRepository: SettingsRepository,
    private val notificationHelper: NotificationHelper,
    private val json: Json,
    private val completedDownloadBus: CompletedDownloadBus,
    private val pendingTranscriptionDao: PendingTranscriptionDao,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(Constants.KEY_DOWNLOAD_ITEM_ID) ?: return Result.failure()
        val entity = dao.getById(id) ?: return Result.failure()
        if (entity.state == DownloadState.CANCELLED || entity.state == DownloadState.COMPLETED) {
            return Result.success()
        }
        val spec = try {
            json.decodeFromString(DownloadSpec.serializer(), entity.specJson)
        } catch (e: Exception) {
            return fail(id, applicationContext.getString(R.string.dl_error_embed_invalid_spec))
        }
        setForeground(
            notificationHelper.foregroundInfo(id, spec.metadata.title, processing = true)
        )
        dao.updateState(id, DownloadState.ACTIVE.name)
        try {
            val videoPath = entity.filePath ?: error("Missing video file for embedding.")
            val subtitlePath = entity.subtitlePath ?: error("Missing subtitle file for embedding.")
            val outputTemp = File(applicationContext.cacheDir, "$id.embed.tmp")
            when (spec.embedMode) {
                EmbedMode.SOFT -> {
                    // CONTRACTS §5: soft-embed language metadata uses translatedLanguageCode
                    // when the embedded track is a translation; falls back to the track's own code.
                    val lang = spec.translatedLanguageCode ?: spec.subtitleTrack?.languageCode
                    ffmpegProcessor.embedSoftSubtitles(
                        videoPath, subtitlePath, lang, outputTemp.absolutePath,
                    ).getOrThrow()
                }
                EmbedMode.HARD -> ffmpegProcessor.burnHardSubtitles(
                    videoPath, subtitlePath, spec.embedStyle, outputTemp.absolutePath,
                ).getOrThrow()
                EmbedMode.NONE -> error("Embed worker started but no embed mode requested.")
            }
            val settings = settingsRepository.current()
            val displayName = "${DownloadWorker.sanitizeFileName(spec.outputFileName)}.mp4"
            val finalUri = FileStorageHelper.finalizeFile(
                applicationContext, settings, outputTemp, displayName, "video/mp4",
            )
            dao.markCompleted(id, finalUri, null)
            notificationHelper.notifyCompleted(id, spec.metadata.title)
            // DURABLE pending-transcription row (idempotent by dedupKey). Inserted after
            // dao.markCompleted; survives process death so transcription is enqueued on
            // next app start even if the in-memory bus emit is lost.
            pendingTranscriptionDao.insertPendingIfAbsent(
                downloadId = id,
                filePath = finalUri,
                subtitlePath = null,
                title = spec.metadata.title,
                sourceUrl = spec.metadata.sourceUrl,
            )
            completedDownloadBus.tryEmit(
                CompletedDownloadBus.Event(
                    downloadId = id,
                    filePath = finalUri,
                    subtitlePath = null,
                    title = spec.metadata.title,
                    sourceUrl = spec.metadata.sourceUrl,
                    completedAtMs = System.currentTimeMillis(),
                ),
            )
            FileStorageHelper.cleanTempFiles(applicationContext, id)
            ResumeDecisionStore.clear(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val kind = RetryPolicy.classify(e)
            val retryAllowed = RetryPolicy.shouldRetry(kind) &&
                !RetryPolicy.embedRetryExhausted(runAttemptCount)
            return if (retryAllowed) {
                Result.retry()
            } else {
                fail(id, e.message ?: applicationContext.getString(R.string.dl_error_embed_failed))
            }
        }
        return Result.success()
    }

    private suspend fun fail(id: String, message: String): Result {
        dao.markFailed(id, message)
        FileStorageHelper.cleanTempFiles(applicationContext, id)
        val title = dao.getById(id)?.title.orEmpty()
        notificationHelper.notifyFailed(id, title, message)
        return Result.failure()
    }

    private fun <T> AppResult<T>.getOrThrow(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Error -> error(message)
    }
}
