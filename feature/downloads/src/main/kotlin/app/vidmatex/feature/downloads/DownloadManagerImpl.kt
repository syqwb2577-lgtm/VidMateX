package app.vidmatex.feature.downloads

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.vidmatex.core.common.AppResult
import app.vidmatex.core.common.Constants
import app.vidmatex.core.common.DownloadManager
import app.vidmatex.core.common.SettingsRepository
import app.vidmatex.core.common.UrlValidator
import app.vidmatex.core.common.safeSuspend
import app.vidmatex.core.database.DownloadDao
import app.vidmatex.core.database.DownloadEntity
import app.vidmatex.core.model.DownloadItem
import app.vidmatex.core.model.DownloadSpec
import app.vidmatex.core.model.DownloadState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

@Singleton
class DownloadManagerImpl @Inject constructor(
    private val dao: DownloadDao,
    private val workManager: WorkManager,
    private val json: Json,
    private val notificationHelper: NotificationHelper,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : DownloadManager {
    /** In-process guard against concurrent duplicate enqueues (double taps / two screens). */
    private val enqueueGuards = ConcurrentHashMap.newKeySet<String>()

    override fun observeAll(): Flow<List<DownloadItem>> =
        dao.observeAll().map { list -> list.map { it.toDownloadItem() } }
    override fun observe(id: String): Flow<DownloadItem?> =
        dao.observeById(id).map { it?.toDownloadItem() }

    override suspend fun enqueue(spec: DownloadSpec): AppResult<String> {
        // Requirement §2/§3: validate before any DB row, worker, or progress state exists.
        // Invalid / private-IP / non-HTTPS / platform-metadata-only specs return a typed,
        // user-visible AppResult.Error — no silent failure and no fake progress.
        val validation = DownloadInputValidator.validate(spec)
        if (!validation.ok) {
            return AppResult.Error(
                message = validationErrorMessage(validation.reason),
                recoverable = validation.recoverable,
            )
        }
        val canonicalUrl = validation.canonicalUrl ?: UrlValidator.canonicalize(spec.metadata.sourceUrl)
        val qualityLabel = DedupPolicy.qualityLabel(spec)
        val guardKey = DedupPolicy.guardKey(canonicalUrl, spec)
        // Prevent two simultaneous enqueue calls for the same URL + kind + quality.
        if (!enqueueGuards.add(guardKey)) {
            return AppResult.Error(context.getString(R.string.dl_error_duplicate), recoverable = true)
        }
        return try {
            // Primary stream kind powers kind-aware dedup: subtitle-only jobs never collide
            // with video/audio jobs that share the same URL and quality label.
            val kind = DownloadKinds.kindFor(spec.mode, spec.audioOnly, spec.videoTrack, spec.audioTrack)
            // Dedup: active/queued job with same canonical URL + quality label + track kind.
            // Kind-aware Room query first; when it returns nothing (e.g. legacy rows written
            // before the v2 trackKind column), fall back to the legacy URL+quality query and
            // confirm parity by decoding the stored spec JSON, preserving old behavior.
            val duplicates = dao.getActiveByUrlQualityAndKind(canonicalUrl, qualityLabel, kind)
                .ifEmpty {
                    dao.getByCanonicalUrlAndQuality(canonicalUrl, qualityLabel).filter { row ->
                        val rowAudioOnly = runCatching {
                            DedupPolicy.isAudioOnly(json.decodeFromString(DownloadSpec.serializer(), row.specJson))
                        }.getOrDefault(false)
                        DedupPolicy.sameKind(rowAudioOnly, DedupPolicy.isAudioOnly(spec))
                    }
                }
            if (duplicates.isNotEmpty()) {
                return AppResult.Error(context.getString(R.string.dl_error_duplicate), recoverable = true)
            }
            safeSuspend {
                val id = UUID.randomUUID().toString()
                val specJson = json.encodeToString(DownloadSpec.serializer(), spec)
                val entity = DownloadEntity(
                    id = id,
                    sourceUrl = canonicalUrl,
                    sourceType = spec.metadata.sourceType,
                    title = spec.metadata.title.ifBlank { "Download" },
                    thumbnailUrl = spec.metadata.thumbnailUrl,
                    qualityLabel = qualityLabel,
                    state = DownloadState.QUEUED,
                    totalBytes = spec.videoTrack?.estimatedBytes ?: spec.audioTrack?.estimatedBytes,
                    specJson = specJson,
                    trackKind = kind,
                )
                dao.upsert(entity)
                enqueueWorker(id, specJson)
                id
            }
        } finally {
            enqueueGuards.remove(guardKey)
        }
    }

    override suspend fun pause(id: String) {
        val entity = dao.getById(id) ?: return
        if (entity.state !in setOf(DownloadState.QUEUED, DownloadState.ACTIVE)) return
        // Pause = cancel the running worker but keep partial bytes + temp file so resume
        // can send a Range header. True pause/resume requires the server to honor Range;
        // if it does not, the worker restarts from zero transparently.
        dao.updateState(id, DownloadState.PAUSED.name)
        workManager.cancelWorkById(WorkIds.download(id))
        workManager.cancelWorkById(WorkIds.embed(id))
        notificationHelper.cancel(id)
        notificationHelper.notifyPaused(id, entity.title, entity.progressPercent)
    }

    override suspend fun resume(id: String) {
        val entity = dao.getById(id) ?: return
        if (entity.state != DownloadState.PAUSED) return
        dao.updateState(id, DownloadState.QUEUED.name)
        enqueueWorker(id, entity.specJson)
    }

    override suspend fun cancel(id: String) {
        val entity = dao.getById(id) ?: return
        if (entity.state in setOf(DownloadState.COMPLETED, DownloadState.CANCELLED)) return
        dao.updateState(id, DownloadState.CANCELLED.name)
        workManager.cancelWorkById(WorkIds.download(id))
        workManager.cancelWorkById(WorkIds.embed(id))
        notificationHelper.cancel(id)
        FileStorageHelper.cleanTempFiles(context, id)
        ResumeDecisionStore.clear(id)
    }

    override suspend fun retry(id: String) {
        val entity = dao.getById(id) ?: return
        if (entity.state !in setOf(DownloadState.FAILED, DownloadState.CANCELLED, DownloadState.PAUSED)) return
        // Keep partial stream bytes only when the server accepted Range; otherwise the
        // worker must fetch from zero, so stale temps are removed first.
        val audioOnly = runCatching {
            DedupPolicy.isAudioOnly(json.decodeFromString(DownloadSpec.serializer(), entity.specJson))
        }.getOrDefault(false)
        val kind = if (audioOnly) DownloadKinds.KIND_AUDIO else DownloadKinds.KIND_VIDEO
        if (!ResumeDecisionStore.isResumable(id, kind)) {
            FileStorageHelper.cleanTempFiles(context, id)
        }
        dao.upsert(
            entity.copy(
                state = DownloadState.QUEUED,
                progressPercent = 0,
                speedBps = 0,
                etaSec = null,
                downloadedBytes = 0,
                errorMessage = null,
                filePath = null,
                subtitlePath = null,
            )
        )
        enqueueWorker(id, entity.specJson)
    }

    override suspend fun delete(id: String, deleteFile: Boolean) {
        val entity = dao.getById(id) ?: return
        workManager.cancelWorkById(WorkIds.download(id))
        workManager.cancelWorkById(WorkIds.embed(id))
        notificationHelper.cancel(id)
        if (deleteFile) {
            FileStorageHelper.deleteStoredPath(context, entity.filePath)
            FileStorageHelper.deleteStoredPath(context, entity.subtitlePath)
        }
        FileStorageHelper.cleanTempFiles(context, id)
        ResumeDecisionStore.clear(id)
        dao.deleteById(id)
    }

    override suspend fun clearHistory() {
        dao.clearHistory()
    }

    private suspend fun enqueueWorker(id: String, specJson: String) {
        val requireWifi = settingsRepository.current().requireWifi
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setId(WorkIds.download(id))
            .addTag(Constants.WORK_TAG_DOWNLOAD)
            .setConstraints(DownloadWorkConstraints.build(requireWifi))
            .setInputData(
                workDataOf(
                    Constants.KEY_DOWNLOAD_ITEM_ID to id,
                    Constants.KEY_DOWNLOAD_SPEC to specJson,
                )
            )
            // Exponential backoff for transient network failures; the worker itself
            // decides retry vs permanent failure via RetryPolicy.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        // Unique per item: KEEP prevents a second schedule for the same id while one is
        // queued/active; pause/cancel first remove the work so resume/retry enqueue cleanly.
        workManager.enqueueUniqueWork(
            WorkIds.downloadWorkName(id),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Maps a pure validation reason to a localized, user-visible error message. */
    private fun validationErrorMessage(reason: DownloadInputValidator.Reason): String = when (reason) {
        DownloadInputValidator.Reason.OK -> ""
        DownloadInputValidator.Reason.BLANK,
        DownloadInputValidator.Reason.MALFORMED,
        DownloadInputValidator.Reason.NO_HOST,
        DownloadInputValidator.Reason.TOO_LONG ->
            context.getString(R.string.dl_error_invalid_url)
        DownloadInputValidator.Reason.BLOCKED_SCHEME,
        DownloadInputValidator.Reason.NON_HTTPS ->
            context.getString(R.string.dl_error_not_https)
        DownloadInputValidator.Reason.PRIVATE_HOST ->
            context.getString(R.string.dl_error_private_host)
        DownloadInputValidator.Reason.PLATFORM_METADATA_ONLY ->
            context.getString(R.string.dl_error_platform_compliance)
        DownloadInputValidator.Reason.UNSUPPORTED_SOURCE ->
            context.getString(R.string.dl_error_unsupported_host)
    }
}

internal fun DownloadEntity.toDownloadItem(): DownloadItem = DownloadItem(
    id = id,
    sourceUrl = sourceUrl,
    sourceType = sourceType,
    title = title,
    thumbnailUrl = thumbnailUrl,
    qualityLabel = qualityLabel,
    state = state,
    progressPercent = progressPercent,
    speedBps = speedBps,
    etaSec = etaSec,
    totalBytes = totalBytes,
    downloadedBytes = downloadedBytes,
    filePath = filePath,
    subtitlePath = subtitlePath,
    errorMessage = errorMessage,
    createdAtMs = createdAtMs,
)
