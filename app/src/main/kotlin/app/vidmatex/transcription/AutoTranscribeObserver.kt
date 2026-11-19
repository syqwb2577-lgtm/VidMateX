package app.vidmatex.transcription

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.vidmatex.core.common.SettingsRepository
import app.vidmatex.core.database.PendingTranscriptionDao
import app.vidmatex.feature.downloads.CompletedDownloadBus
import app.vidmatex.feature.subtitles.transcription.CompletedAudioHook
import app.vidmatex.feature.subtitles.transcription.TranscriptionDedup
import app.vidmatex.feature.subtitles.transcription.TranscriptionWork
import app.vidmatex.feature.subtitles.transcription.TranscriptionWorker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Durable post-download auto-transcription observer.
 *
 * Lives in `:app` (the only module depending on `:feature:subtitles` + `:feature:downloads`
 * + `:feature:settings`). Started once from [app.vidmatex.VidMateXApplication.onCreate].
 *
 * DURABILITY (fixes the in-memory-bus gap): the old design relied solely on
 * [CompletedDownloadBus] (in-process, replay=0). If the app process died between download
 * completion and transcription enqueue, the completed download was lost and never
 * transcribed. Now [app.vidmatex.core.database.PendingTranscriptionEntity] rows are
 * inserted atomically with download completion (in DownloadWorker/EmbedWorker, after
 * `dao.markCompleted`). On [start] this observer scans `status = PENDING` rows and
 * enqueues transcription for each — so a download completed while the process was dead is
 * picked up on the next app start.
 *
 * IDEMPOTENCY (same file never transcribed twice) — three independent layers:
 *  1. DB UNIQUE index on `dedupKey` + INSERT OR IGNORE (`insertPendingIfAbsent`): a second
 *     completion of the same file never creates a second pending row.
 *  2. WorkManager `enqueueUniqueWork("transcribe_$dedupKey", KEEP)`: even if the observer
 *     enqueues twice (app-start scan + live event, or two app starts, or a retry), only one
 *     Worker job exists. The in-memory ConcurrentHashMap dedup is REMOVED — the DB +
 *     WorkManager replace it.
 *  3. Terminal status transitions (SUCCEEDED/FAILED/CANCELLED) keyed by `dedupKey`;
 *     `findPending` returns only status=PENDING, so terminal rows are never re-enqueued.
 *
 * The `dedupKey` is computed from the STORED filePath (not the resolved cache path) so the
 * worker insert (in feature/downloads, which cannot depend on feature/subtitles) and this
 * observer agree on the key. [PendingTranscriptionKeys.keyFor] (core/database) and
 * [TranscriptionDedup.keyFor] (feature/subtitles) produce identical SHA-256 hex output;
 * equality is asserted by a unit test.
 */
@Singleton
class AutoTranscribeObserver @Inject constructor(
    private val completedDownloadBus: CompletedDownloadBus,
    private val settingsRepository: SettingsRepository,
    private val pendingTranscriptionDao: PendingTranscriptionDao,
    private val enqueuer: TranscriptionJobEnqueuer,
    private val pathResolver: LocalFilePathResolver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Start observing: (1) scan durable pending rows immediately (covers process-death
     * gap), then (2) collect live completion events for low-latency enqueue. Both paths
     * are idempotent (DB unique index + WorkManager KEEP).
     */
    fun start() {
        scope.launch { scanPending() }
        scope.launch {
            completedDownloadBus.events.collect { event -> handleLiveEvent(event) }
        }
    }

    /**
     * Durable pending scan. Called on app start (and exposed for tests). Reads settings
     * once; if auto-transcribe is disabled or the Whisper endpoint is unconfigured, ALL
     * rows are left PENDING so they retry on the next start when the user enables them.
     * Rows whose file is not audio/video or cannot be resolved are marked CANCELLED
     * (terminal, no retry) to avoid permanent PENDING churn.
     */
    suspend fun scanPending() = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()
        if (!settings.autoTranscribe || settings.whisperEndpoint.isBlank()) return@withContext
        val pending = pendingTranscriptionDao.findPending()
        for (row in pending) {
            enqueueForRow(row.filePath, row.dedupKey, settings.requireWifi)
        }
    }

    /**
     * Live completion handler. Re-inserts the pending row idempotently (worker already
     * did; belt-and-suspenders) then enqueues. If settings are off, the row stays
     * PENDING (picked up by a future scan).
     */
    private suspend fun handleLiveEvent(event: CompletedDownloadBus.Event) =
        withContext(Dispatchers.IO) {
            pendingTranscriptionDao.insertPendingIfAbsent(
                downloadId = event.downloadId,
                filePath = event.filePath,
                subtitlePath = event.subtitlePath,
                title = event.title,
                sourceUrl = event.sourceUrl,
            )
            val settings = settingsRepository.settings.first()
            if (!settings.autoTranscribe || settings.whisperEndpoint.isBlank()) return@withContext
            // dedupKey from the STORED filePath — must match PendingTranscriptionKeys.keyFor.
            val dedupKey = TranscriptionDedup.keyFor(event.filePath)
            if (dedupKey.isBlank()) return@withContext
            enqueueForRow(event.filePath, dedupKey, settings.requireWifi)
        }

    /**
     * Enqueue transcription for one pending row. Resolves to a real filesystem path,
     * guards on audio/video containers, enqueues unique work (KEEP), and marks the row
     * ENQUEUED. Non-transcribable rows are marked CANCELLED. Enqueue failures leave the
     * row PENDING (retry on next scan).
     */
    private suspend fun enqueueForRow(
        storedFilePath: String,
        dedupKey: String,
        requireWifi: Boolean,
    ) {
        val localPath = pathResolver.resolve(storedFilePath)
        if (localPath == null || !CompletedAudioHook.audioLike(localPath)) {
            pendingTranscriptionDao.markNotTranscribable(
                dedupKey = dedupKey,
                errorMessage = if (localPath == null) "unresolvable_path" else "not_audio_video",
                nowMs = System.currentTimeMillis(),
            )
            return
        }
        val enqueued = runCatching {
            enqueuer.enqueueUnique(
                audioPath = localPath,
                sourceLang = "", // blank = auto-detect (TranscriptionWorker contract)
                requireWifi = requireWifi,
                dedupKey = dedupKey,
            )
        }.isSuccess
        if (enqueued) {
            pendingTranscriptionDao.markEnqueued(dedupKey, System.currentTimeMillis())
        }
        // On enqueue failure the row stays PENDING and will be retried on next app start.
    }
}

/**
 * Enqueues a unique (idempotent) transcription job. Wraps WorkManager so the observer is
 * testable with a fake. Real impl: [WorkManagerTranscriptionJobEnqueuer].
 */
interface TranscriptionJobEnqueuer {
    /**
     * Enqueue with unique name `transcribe_$dedupKey` and [ExistingWorkPolicy.KEEP] — a
     * second call for the same dedupKey is a no-op. Returns after enqueue; failures
     * propagate as exceptions (caller leaves the row PENDING for retry).
     */
    fun enqueueUnique(audioPath: String, sourceLang: String, requireWifi: Boolean, dedupKey: String)
}

/** Resolves a stored filePath (absolute / file:// / content://) to a real filesystem path. */
interface LocalFilePathResolver {
    fun resolve(filePath: String): String?
}

@Singleton
class WorkManagerTranscriptionJobEnqueuer @Inject constructor(
    private val workManager: WorkManager,
) : TranscriptionJobEnqueuer {
    override fun enqueueUnique(audioPath: String, sourceLang: String, requireWifi: Boolean, dedupKey: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (requireWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .addTag(TranscriptionWork.TAG)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30_000L, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    TranscriptionWorker.KEY_AUDIO_PATH to audioPath,
                    TranscriptionWorker.KEY_SOURCE_LANG to sourceLang,
                    TranscriptionWorker.KEY_JOB_ID to "auto_$dedupKey",
                    TranscriptionWorker.KEY_DEDUP_KEY to dedupKey,
                )
            )
            .build()
        // ExistingWorkPolicy.KEEP = true idempotency: if a job with this unique name
        // already exists (enqueued by a prior scan / live event / app start), the new
        // request is silently dropped. Same file is never transcribed twice.
        workManager.enqueueUniqueWork("transcribe_$dedupKey", ExistingWorkPolicy.KEEP, request)
    }
}

@Singleton
class DefaultLocalFilePathResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocalFilePathResolver {
    /**
     * Resolve a stored filePath (absolute path, `file://`, or `content://`) to a real
     * filesystem path that [java.io.File] can open. `content://` (MediaStore/SAF) is
     * copied into cache because TranscriptionWorker cannot open a content stream directly.
     */
    override fun resolve(filePath: String): String? {
        val trimmed = filePath.trim()
        return when {
            trimmed.startsWith("/") -> trimmed.takeIf { File(it).isFile }
            trimmed.startsWith("file://", ignoreCase = true) ->
                Uri.parse(trimmed).path?.takeIf { File(it).isFile }
            trimmed.startsWith("content://", ignoreCase = true) -> copyContentToCache(trimmed)
            else -> null
        }
    }

    private fun copyContentToCache(contentUri: String): String? = runCatching {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(contentUri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val out = File(context.cacheDir, "transcribe_src_$hash.tmp")
        if (out.isFile && out.length() > 0L) return out.absolutePath
        context.contentResolver.openInputStream(Uri.parse(contentUri))?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: return null
        out.absolutePath.takeIf { File(it).isFile && File(it).length() > 0L }
    }.getOrNull()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AutoTranscribeObserverModule {
    @Binds
    @Singleton
    abstract fun bindTranscriptionJobEnqueuer(
        impl: WorkManagerTranscriptionJobEnqueuer,
    ): TranscriptionJobEnqueuer

    @Binds
    @Singleton
    abstract fun bindLocalFilePathResolver(
        impl: DefaultLocalFilePathResolver,
    ): LocalFilePathResolver
}
