package app.vidmatex.feature.downloads

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.vidmatex.core.common.AppResult
import app.vidmatex.core.common.Constants
import app.vidmatex.core.common.DispatcherProvider
import app.vidmatex.core.common.SettingsRepository
import app.vidmatex.core.database.DownloadDao
import app.vidmatex.core.database.PendingTranscriptionDao
import app.vidmatex.core.model.DownloadMode
import app.vidmatex.core.model.DownloadSpec
import app.vidmatex.core.model.DownloadState
import app.vidmatex.core.model.EmbedMode
import app.vidmatex.core.model.MediaTrack
import app.vidmatex.media.processor.FFmpegProcessor
import app.vidmatex.source.adapters.MediaSourceResolver
import app.vidmatex.source.adapters.SourceAdapter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.OkHttpClient

/**
 * Streams the resolved media to a cache temp file (HTTP Range resume from any partial
 * bytes left by a pause), optionally muxes separate video+audio tracks, fetches the
 * subtitle track when requested, then either finalizes the file(s) to MediaStore/SAF or
 * hands off to [EmbedWorker] when soft/hard subtitle embedding is requested.
 *
 * Lifecycle: resolve adapter → build stream URLs → download video/audio streams with the
 * resume policy below → (audio-only: no mux) → finalize to MediaStore/SAF → upsert Room
 * row; subtitle content follows the serialized-content-first contract; embedding hands
 * off to [EmbedWorker].
 *
 * Resume policy (contract task 2): a partial temp file gets a `Range` header only when
 * the track's `resumable` flag says so, or a lightweight HEAD probe returns
 * `Accept-Ranges: bytes`. A 206 response appends; a 200 response (server ignores Range)
 * restarts from zero — bytes are never appended onto a full-200 body. The decision is
 * recorded in [ResumeDecisionStore] (process-local cache) and persisted to the Room v2
 * `DownloadEntity.resumable` column via `dao.updateResumable(...)` after each probe/response.
 * A persistent DataStore fallback map (`Constants.PREF_RESUME_MAP_JSON`) remains a recorded
 * follow-up; absent persistence always means re-probe and never appending unverified bytes.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: DownloadDao,
    private val okHttpClient: OkHttpClient,
    private val resolver: MediaSourceResolver,
    private val ffmpegProcessor: FFmpegProcessor,
    private val settingsRepository: SettingsRepository,
    private val notificationHelper: NotificationHelper,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
    private val completedDownloadBus: CompletedDownloadBus,
    private val pendingTranscriptionDao: PendingTranscriptionDao,
) : CoroutineWorker(context, params) {
    private val itemId: String? get() = inputData.getString(Constants.KEY_DOWNLOAD_ITEM_ID)

    override suspend fun doWork(): Result {
        // Worker input-data contract (pure policy): missing id/spec → permanent failure,
        // never a guessed URL or fake progress.
        val inputs = WorkerInputPolicy.parse(
            itemId,
            inputData.getString(Constants.KEY_DOWNLOAD_SPEC),
        ) ?: return Result.failure()
        val id = inputs.itemId
        val specJson = inputs.specJson
        val spec = try {
            json.decodeFromString(DownloadSpec.serializer(), specJson)
        } catch (e: Exception) {
            return fail(id, "Invalid download spec: ${e.message}", RetryPolicy.Kind.MISSING_FILE)
        }
        // Defense in depth: the manager validates before enqueue; the worker re-checks the
        // spec URL (including notification-action re-enqueues) and rejects platform pages,
        // private/loopback hosts, and non-HTTPS direct URLs before any network access.
        val specValidation = DownloadInputValidator.validate(spec)
        if (!specValidation.ok) {
            return fail(
                id,
                validationMessage(specValidation.reason),
                RetryPolicy.Kind.UNSUPPORTED_URL,
            )
        }
        val entity = dao.getById(id) ?: return Result.failure()
        if (entity.state == DownloadState.CANCELLED || entity.state == DownloadState.COMPLETED) {
            return Result.success()
        }
        setForeground(
            notificationHelper.foregroundInfo(id, spec.metadata.title, indeterminate = true)
        )
        dao.updateState(id, DownloadState.ACTIVE.name)
        try {
            val settings = settingsRepository.current()
            if (spec.mode == DownloadMode.SUBTITLE_ONLY) {
                downloadSubtitleOnly(id, spec, settings)
            } else {
                downloadMedia(id, spec, settings, entity)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = (e as? HttpException)?.code
            val kind = RetryPolicy.classify(e, code)
            return fail(id, friendlyMessage(kind, e.message, code), kind)
        }
        return Result.success()
    }

    // ---- media (video / audio / video+audio; audio-only supported structurally) ----
    private suspend fun downloadMedia(
        id: String,
        spec: DownloadSpec,
        settings: app.vidmatex.core.model.AppSettings,
        entity: app.vidmatex.core.database.DownloadEntity,
    ) {
        val adapter = when (val r = resolver.adapterFor(spec.metadata.sourceUrl)) {
            is AppResult.Success -> r.value
            is AppResult.Error -> error(r.message)
        }
        // Prefer the explicit AUDIO_ONLY mode / audioOnly flag; fall back to the structural
        // rule (videoTrack == null && audioTrack != null) for older serialized specs that
        // predate those additive fields.
        val audioOnly = spec.mode == DownloadMode.AUDIO_ONLY || spec.audioOnly ||
            DownloadKinds.isAudioOnly(spec.videoTrack, spec.audioTrack)
        val videoUrl = spec.videoTrack?.let { validatedStreamUrl(adapter.streamUrl(spec.metadata, it).getOrThrow()) }
        val audioUrl = spec.audioTrack?.let { validatedStreamUrl(adapter.streamUrl(spec.metadata, it).getOrThrow()) }
        if (videoUrl == null && audioUrl == null) error("No stream available for the selected track.")
        val cache = applicationContext.cacheDir
        val tempVideo = File(cache, "$id.video.tmp")
        val tempAudio = File(cache, "$id.audio.tmp")
        val title = spec.metadata.title
        var videoTotal: Long? = null
        var audioTotal: Long? = null
        var videoDl = 0L
        var audioDl = 0L
        val combinedUpdate: suspend (Long, Long?, Long) -> Unit = { dl, total, speed ->
            val percent = if (total != null && total > 0) ((dl * 100) / total).toInt().coerceIn(0, 99) else 0
            val eta = total?.minus(dl)?.let { SpeedEstimator.etaSeconds(it, speed) }
            dao.updateProgress(id, percent, speed, eta, total, dl)
            setProgress(
                workDataOf(
                    "progress" to percent,
                    "speedBps" to speed,
                    "etaSec" to (eta ?: -1L),
                )
            )
            notificationHelper.notifyProgress(id, title, percent, speed, eta, total == null)
        }
        if (videoUrl != null) {
            downloadStream(videoUrl, tempVideo, spec.videoTrack, id, DownloadKinds.KIND_VIDEO) { dl, total, speed ->
                videoDl = dl; videoTotal = total
                combinedUpdate(dl + audioDl, sumOrNull(videoTotal, audioTotal), speed)
            }
        }
        if (audioUrl != null) {
            downloadStream(audioUrl, tempAudio, spec.audioTrack, id, DownloadKinds.KIND_AUDIO) { dl, total, speed ->
                audioDl = dl; audioTotal = total
                combinedUpdate(videoDl + dl, sumOrNull(videoTotal, audioTotal), speed)
            }
        }
        val muxed = videoUrl != null && audioUrl != null
        val primaryTemp: File = if (muxed) {
            val muxOut = File(cache, "$id.mux.tmp")
            ffmpegProcessor.muxVideoAudio(tempVideo.absolutePath, tempAudio.absolutePath, muxOut.absolutePath)
                .getOrThrow()
            muxOut
        } else {
            if (videoUrl != null) tempVideo else tempAudio
        }
        // Audio-only jobs keep the audio container extension/mime and never mux.
        val finalExt = when {
            muxed -> "mp4"
            audioOnly -> DownloadKinds.audioExtension(spec.audioTrack?.container)
            else -> (spec.videoTrack ?: spec.audioTrack)?.container?.lowercase() ?: "mp4"
        }
        val mime = if (audioOnly) FileStorageHelper.audioMime(finalExt) else FileStorageHelper.mediaMime(finalExt)
        val baseName = sanitizeFileName(spec.outputFileName)
        val displayName = "$baseName.$finalExt"
        val subTrack = spec.subtitleTrack
        var completedFilePath: String?
        var completedSubtitlePath: String? = null
        if (spec.mode == DownloadMode.VIDEO_AND_SUBTITLE && subTrack != null && !audioOnly) {
            // ---- CONTRACTS §5: prefer serialized subtitle content from the spec ----
            // The Subtitles screen already fetched, parsed, (optionally) translated, shifted,
            // and re-serialized the cues. We must NOT re-fetch via the adapter — that would
            // lose translation, shift, and format choices. Only when BOTH contents are null
            // (legacy spec, e.g. enqueued from Home with no subtitle preview) do we fall back.
            val chosenSubText: String = resolveSubtitleText(spec, adapter)
            val tempSub = File(cache, "$id.sub.tmp")
            withContext(dispatchers.io) { tempSub.writeText(chosenSubText, Charsets.UTF_8) }
            // When translation is embedded and the user asked to keep the original as a
            // sidecar, write the original content to a second temp file for finalization.
            val origContent = spec.subtitleContentOriginal
            val originalSidecarTemp: File? = if (
                spec.useTranslatedSubtitle && spec.saveOriginalSubtitleSidecar && origContent != null
            ) {
                File(cache, "$id.sub.orig.tmp").also {
                    withContext(dispatchers.io) { it.writeText(origContent, Charsets.UTF_8) }
                }
            } else null
            if (spec.embedMode != EmbedMode.NONE) {
                // Hand off to EmbedWorker; keep ACTIVE (processing). Temp paths stored in entity.
                // specJson is preserved by copy() (entity was loaded from DB with specJson set).
                dao.upsert(
                    entity.copy(
                        state = DownloadState.ACTIVE,
                        filePath = primaryTemp.absolutePath,
                        subtitlePath = tempSub.absolutePath,
                        progressPercent = 95,
                        speedBps = 0,
                        etaSec = null,
                    )
                )
                enqueueEmbedWorker(id)
                notificationHelper.notifyProcessing(id, title)
                return
            }
            // Soft-embed off: write chosen subtitle as a sidecar file next to the video.
            val subExt = FileStorageHelper.subtitleExtension(spec.subtitleFormat)
            val finalVideo = FileStorageHelper.finalizeFile(applicationContext, settings, primaryTemp, displayName, mime)
            val finalSub = FileStorageHelper.finalizeFile(
                applicationContext, settings, tempSub,
                "$baseName.$subExt", FileStorageHelper.subtitleMime(spec.subtitleFormat),
            )
            // Also finalize the original-language sidecar when translation was used and requested.
            originalSidecarTemp?.let { orig ->
                val origExt = FileStorageHelper.subtitleExtension(spec.subtitleFormat)
                FileStorageHelper.finalizeFile(
                    applicationContext, settings, orig,
                    "${baseName}_original.$origExt", FileStorageHelper.subtitleMime(spec.subtitleFormat),
                )
            }
            completedFilePath = finalVideo
            completedSubtitlePath = finalSub
            dao.markCompleted(id, finalVideo, finalSub)
        } else {
            val finalVideo = FileStorageHelper.finalizeFile(applicationContext, settings, primaryTemp, displayName, mime)
            completedFilePath = finalVideo
            dao.markCompleted(id, finalVideo, null)
        }
        notificationHelper.notifyCompleted(id, title)
        completedFilePath.let { path ->
            // DURABLE pending-transcription row: inserted after dao.markCompleted so only
            // fully-written files are queued. Idempotent by dedupKey (UNIQUE index +
            // INSERT OR IGNORE); survives process death. The in-memory bus emit below is
            // best-effort — the observer's app-start scan re-enqueues any row left PENDING.
            pendingTranscriptionDao.insertPendingIfAbsent(
                downloadId = id,
                filePath = path,
                subtitlePath = completedSubtitlePath,
                title = title,
                sourceUrl = spec.metadata.sourceUrl,
            )
            completedDownloadBus.tryEmit(
                CompletedDownloadBus.Event(
                    downloadId = id,
                    filePath = path,
                    subtitlePath = completedSubtitlePath,
                    title = title,
                    sourceUrl = spec.metadata.sourceUrl,
                    completedAtMs = System.currentTimeMillis(),
                ),
            )
        }
        FileStorageHelper.cleanTempFiles(applicationContext, id)
        ResumeDecisionStore.clear(id)
    }

    /**
     * Pick the subtitle text to embed/download. Priority:
     *  1. translated content when [DownloadSpec.useTranslatedSubtitle] is set
     *  2. original serialized content when present
     *  3. legacy fallback: re-fetch via the adapter (both contents null)
     */
    private suspend fun resolveSubtitleText(
        spec: DownloadSpec,
        adapter: SourceAdapter?,
    ): String {
        // Local vals: DownloadSpec is a cross-module public API, so its nullable
        // properties cannot be smart-cast after a null check (custom getter possible).
        val translated = spec.subtitleContentTranslated
        if (spec.useTranslatedSubtitle && translated != null) {
            return translated
        }
        val original = spec.subtitleContentOriginal
        if (original != null) {
            return original
        }
        // Legacy fallback: spec was enqueued without pre-fetched subtitle content.
        val fallback = adapter ?: error("No subtitle content available for this job.")
        val subTrack = spec.subtitleTrack ?: error("No subtitle track selected.")
        return fallback.fetchSubtitle(spec.metadata, subTrack, spec.subtitleFormat).getOrThrow()
    }

    // ---- subtitle-only ----
    private suspend fun downloadSubtitleOnly(id: String, spec: DownloadSpec, settings: app.vidmatex.core.model.AppSettings) {
        // Serialized subtitle content (preview / translation / standalone transcription
        // already written into the spec) takes priority; the adapter is resolved only as
        // a legacy fallback. Standalone transcripts (CHANGES_REQUESTED.md §C4) carry local
        // synthetic metadata that has no resolvable remote adapter, so adapter resolution
        // is skipped whenever serialized content is present.
        val hasSerializedContent = spec.subtitleContentOriginal != null ||
            spec.subtitleContentTranslated != null
        val adapter: SourceAdapter? = if (hasSerializedContent) {
            null
        } else when (val r = resolver.adapterFor(spec.metadata.sourceUrl)) {
            is AppResult.Success -> r.value
            is AppResult.Error -> error(r.message)
        }
        // CONTRACTS §5: use serialized content when present; fall back to adapter fetch.
        val subText = resolveSubtitleText(spec, adapter)
        val tempSub = File(applicationContext.cacheDir, "$id.sub.tmp")
        withContext(dispatchers.io) { tempSub.writeText(subText, Charsets.UTF_8) }
        val ext = FileStorageHelper.subtitleExtension(spec.subtitleFormat)
        val finalUri = FileStorageHelper.finalizeFile(
            applicationContext, settings, tempSub,
            "${sanitizeFileName(spec.outputFileName)}.$ext",
            FileStorageHelper.subtitleMime(spec.subtitleFormat),
        )
        // If translation was used and original sidecar requested, also finalize original.
        val origContentSubOnly = spec.subtitleContentOriginal
        if (spec.useTranslatedSubtitle && spec.saveOriginalSubtitleSidecar && origContentSubOnly != null) {
            val origTemp = File(applicationContext.cacheDir, "$id.sub.orig.tmp")
            withContext(dispatchers.io) { origTemp.writeText(origContentSubOnly, Charsets.UTF_8) }
            FileStorageHelper.finalizeFile(
                applicationContext, settings, origTemp,
                "${sanitizeFileName(spec.outputFileName)}_original.$ext",
                FileStorageHelper.subtitleMime(spec.subtitleFormat),
            )
        }
        dao.markCompleted(id, finalUri, null)
        notificationHelper.notifyCompleted(id, spec.metadata.title)
        // DURABLE pending-transcription row (idempotent by dedupKey). Subtitle-only files
        // are not audio/video; the observer marks them CANCELLED on scan (terminal, no
        // retry) so they do not linger in PENDING.
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
    }

    // ---- streaming with Range resume ----
    /**
     * Streams [url] to [tempFile]. Resume decision (see [ResumePolicy]):
     *  1. If a partial file exists and [MediaTrack.resumable] is true, send `Range`.
     *  2. If the flag is unknown/false, issue a lightweight HEAD probe for
     *     `Accept-Ranges: bytes` first.
     *  3. A 206 response appends; a 200/416 restarts from zero (the partial is deleted
     *     and the body written from byte zero — never appended onto a 200).
     * Calls [onUpdate] ~every 800ms with downloaded bytes, known total, and current speed.
     */
    private suspend fun downloadStream(
        url: String,
        tempFile: File,
        track: MediaTrack?,
        itemId: String,
        kind: String,
        onUpdate: suspend (downloaded: Long, total: Long?, speedBps: Long) -> Unit,
    ) = withContext(dispatchers.io) {
        var existing = if (tempFile.exists()) tempFile.length() else 0L
        if (existing > 0L) {
            val probeHeader = if (track?.resumable != true) headAcceptRanges(url) else null
            val allowed = ResumePolicy.probeAllowed(track?.resumable, probeHeader)
            ResumeDecisionStore.mark(itemId, kind, allowed)
            if (!allowed) {
                // Server does not support ranges: discard partial, fetch the full body.
                existing = 0L
                tempFile.delete()
            }
        }
        val request = Request.Builder().url(url).apply {
            if (existing > 0L) header("Range", "bytes=$existing-")
        }.build()
        okHttpClient.newCall(request).execute().use { resp ->
            val decision = ResumePolicy.decide(existing, resp.code, resp.header("Accept-Ranges"))
            val append = ResumePolicy.appends(decision)
            // I-5: persist the Range-resume verdict on the Room row (v2 `resumable` column).
            // Best-effort: a DB failure must never abort an in-flight download; the
            // in-memory [ResumeDecisionStore] remains the fast cache for this process run.
            runCatching { dao.updateResumable(itemId, append) }
            if (existing > 0L && !append) {
                existing = 0L
                tempFile.delete()
            }
            if (decision == ResumePolicy.Decision.UNSUPPORTED_RANGE) {
                throw HttpException(resp.code, "Stream server returned HTTP ${resp.code}")
            }
            val body = resp.body ?: error("Empty response body")
            // Content-Type allowlist: only video/audio containers (and the neutral octet-stream
            // used by direct-file CDNs) are accepted; HTML/JSON/error pages must never be saved
            // as media.
            val contentType = resp.header("Content-Type")
            if (contentType != null && !contentTypeAllowed(contentType)) {
                throw HttpException(resp.code, "Unsupported content type: $contentType")
            }
            val contentLength = RangeHeaderPolicy.parseContentLength(resp.header("Content-Length"))
                ?: body.contentLength().takeIf { it >= 0L }
            val total: Long? = if (append) {
                RangeHeaderPolicy.totalForResumedResponse(
                    resp.header("Content-Range"), existing, contentLength ?: -1L,
                )
            } else {
                contentLength
            }
            // Checksum verification only for a fresh body: a 206 append covers a byte range,
            // not the whole file, so a running MD5 would not represent the final file.
            val expectedMd5 = if (!append) {
                IntegrityPolicy.parseContentMd5(resp.header("Content-MD5"))
            } else null
            val digest: MessageDigest? = expectedMd5?.let { MessageDigest.getInstance("MD5") }
            var downloaded = existing
            val output = java.io.FileOutputStream(tempFile, append).buffered()
            val input = body.byteStream().buffered()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val speedEstimator = SpeedEstimator()
            var lastMark = System.currentTimeMillis()
            try {
                while (true) {
                    if (isStopped) throw CancellationException("Download stopped")
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    digest?.update(buffer, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (now - lastMark >= 800L) {
                        val speed = speedEstimator.update(downloaded, now)
                        onUpdate(downloaded, total, speed)
                        lastMark = now
                    }
                }
                output.flush()
            } finally {
                runCatching { output.close() }
                runCatching { input.close() }
            }
            // ---- Integrity verification (requirement §8) ----
            val sizeVerdict = IntegrityPolicy.verifySize(total, tempFile.length())
            if (sizeVerdict == IntegrityVerdict.SIZE_MISMATCH) {
                throw IntegrityException(
                    "Integrity check failed: expected $total bytes but received " +
                        "${tempFile.length()} bytes (incomplete download)."
                )
            }
            if (expectedMd5 != null) {
                val actualDigest = digest?.digest()
                if (actualDigest == null || !expectedMd5.contentEquals(actualDigest)) {
                    throw IntegrityException("Integrity check failed: Content-MD5 checksum mismatch for $url.")
                }
            }
        }
    }

    /** Allowlisted response MIME types for a media stream (parameters after `;` ignored). */
    private fun contentTypeAllowed(contentType: String): Boolean {
        val mime = contentType.lowercase().substringBefore(';').trim()
        return mime.startsWith("video/") ||
            mime.startsWith("audio/") ||
            mime == "application/octet-stream"
    }

    /** Re-validates an adapter-returned stream URL through the pure security policy. */
    private fun validatedStreamUrl(url: String): String {
        val result = DownloadInputValidator.validateStreamUrl(url)
        if (!result.ok) {
            error("Unsupported stream URL for this source (${result.reason}).")
        }
        return url
    }

    /** Localized message for a worker-side URL/spec validation rejection. */
    private fun validationMessage(reason: DownloadInputValidator.Reason): String = when (reason) {
        DownloadInputValidator.Reason.OK -> ""
        DownloadInputValidator.Reason.BLANK,
        DownloadInputValidator.Reason.MALFORMED,
        DownloadInputValidator.Reason.NO_HOST,
        DownloadInputValidator.Reason.TOO_LONG ->
            applicationContext.getString(R.string.dl_error_invalid_url)
        DownloadInputValidator.Reason.BLOCKED_SCHEME,
        DownloadInputValidator.Reason.NON_HTTPS ->
            applicationContext.getString(R.string.dl_error_not_https)
        DownloadInputValidator.Reason.PRIVATE_HOST ->
            applicationContext.getString(R.string.dl_error_private_host)
        DownloadInputValidator.Reason.PLATFORM_METADATA_ONLY ->
            applicationContext.getString(R.string.dl_error_platform_compliance)
        DownloadInputValidator.Reason.UNSUPPORTED_SOURCE ->
            applicationContext.getString(R.string.dl_error_unsupported_host)
    }

    /** Lightweight HEAD probe for `Accept-Ranges`; failures default to "no resume". */
    private fun headAcceptRanges(url: String): String? = runCatching {
        val head = Request.Builder().url(url).head().build()
        okHttpClient.newCall(head).execute().use { it.header("Accept-Ranges") }
    }.getOrNull()

    private fun enqueueEmbedWorker(id: String) {
        val request = OneTimeWorkRequestBuilder<EmbedWorker>()
            .setId(WorkIds.embed(id))
            .addTag(Constants.WORK_TAG_EMBED)
            .setInputData(workDataOf(Constants.KEY_DOWNLOAD_ITEM_ID to id))
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            WorkIds.embedWorkName(id),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Fail path: persists the error row, applies the temp-file cleanup policy (partial
     * stream kept only when the server accepted Range), posts the failure notification,
     * and returns `Result.retry()` only for transient classes; everything else `Result.failure()`.
     */
    private suspend fun fail(id: String, message: String, kind: RetryPolicy.Kind): Result {
        dao.markFailed(id, message)
        val keepPartial = ResumeDecisionStore.isResumable(id, DownloadKinds.KIND_VIDEO) ||
            ResumeDecisionStore.isResumable(id, DownloadKinds.KIND_AUDIO)
        FileStorageHelper.cleanupForFailure(applicationContext, id, keepPartial)
        val title = dao.getById(id)?.title.orEmpty()
        notificationHelper.notifyFailed(id, title, message)
        return if (RetryPolicy.shouldRetry(kind)) Result.retry() else Result.failure()
    }

    /** Map a pure error classifier to a localized, friendly user-facing message. */
    private fun friendlyMessage(kind: RetryPolicy.Kind, raw: String?, httpCode: Int?): String = when (kind) {
        RetryPolicy.Kind.NETWORK, RetryPolicy.Kind.TIMEOUT ->
            applicationContext.getString(R.string.dl_error_network)
        RetryPolicy.Kind.RATE_LIMIT ->
            applicationContext.getString(R.string.dl_error_server, httpCode?.toString() ?: "429")
        RetryPolicy.Kind.SERVER_5XX ->
            applicationContext.getString(R.string.dl_error_server, httpCode?.toString() ?: "5xx")
        RetryPolicy.Kind.PERMANENT_4XX ->
            applicationContext.getString(R.string.dl_error_permanent_http, httpCode?.toString() ?: "4xx")
        RetryPolicy.Kind.MISSING_FILE ->
            applicationContext.getString(R.string.dl_error_missing_stream)
        RetryPolicy.Kind.STORAGE ->
            applicationContext.getString(R.string.dl_error_storage)
        RetryPolicy.Kind.PERMISSION ->
            applicationContext.getString(R.string.dl_error_permission)
        RetryPolicy.Kind.UNSUPPORTED_FORMAT ->
            applicationContext.getString(R.string.dl_error_unsupported)
        RetryPolicy.Kind.INTEGRITY ->
            applicationContext.getString(R.string.dl_error_integrity)
        RetryPolicy.Kind.UNSUPPORTED_URL ->
            raw?.take(160)?.takeIf { it.isNotBlank() }
                ?: applicationContext.getString(R.string.dl_error_invalid_url)
        RetryPolicy.Kind.RESOLVER ->
            applicationContext.getString(R.string.dl_error_resolver)
        RetryPolicy.Kind.CANCELLED ->
            raw ?: applicationContext.getString(R.string.dl_state_cancelled)
        RetryPolicy.Kind.UNKNOWN ->
            raw?.take(160)?.takeIf { it.isNotBlank() }
                ?: applicationContext.getString(R.string.dl_error_unknown)
    }

    private fun <T> AppResult<T>.getOrThrow(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Error -> error(message)
    }

    /** Carries an HTTP status through the exception chain so [RetryPolicy] can classify it. */
    private class HttpException(val code: Int, message: String) : IOException(message)

    /** Integrity failure (size mismatch / checksum mismatch); permanent, never retried. */
    private class IntegrityException(message: String) : IOException(message)

    companion object {
        internal fun parseContentRangeTotal(header: String?): Long? =
            RangeHeaderPolicy.parseContentRangeTotal(header)

        internal fun sumOrNull(a: Long?, b: Long?): Long? = when {
            a != null && b != null -> a + b
            a != null -> a
            b != null -> b
            else -> null
        }

        internal fun sanitizeFileName(name: String): String = FileNamePolicy.sanitize(name)
    }
}
