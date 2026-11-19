package app.vidmatex.media.processor

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.common.DispatcherProvider
import app.vidmatex.core.model.EmbedStyle
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * FFmpeg operations backed by the maintained FFmpegKit GPL fork
 * (`dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9`; see `docs/FFMPEG.md` §1). The
 * fork keeps the same `com.arthenica.ffmpegkit.*` Java API as the retired Arthenica
 * release; the retired Maven coordinates (`com.arthenica:mobile-ffmpeg-*`,
 * `com.arthenica:ffmpeg-kit-*:6.x`) MUST NOT appear in any build script. The GPL build
 * ships libx264/libx265 required for hard burn; the LGPL sibling
 * (`dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9`) drops those encoders and is a
 * documented relicensing alternative only.
 *
 * Architecture:
 *  - Every vector comes from the pure [FFmpegJobPlanner] (JVM-tested via [FFmpegJob]);
 *    a user string is never concatenated into a shell command — tokens are a
 *    `List<String>` passed to `executeWithArgumentsAsync`.
 *  - [FFmpegJob] + [StorageEstimate] describe the job and estimate output bytes; the
 *    storage pre-check compares the estimate against `File.usableSpace` before launch.
 *  - Outcomes map to [FFmpegError] types (non-zero exit, native unavailable, cancelled,
 *    insufficient storage, invalid input, unknown) before becoming [AppResult.Error].
 *
 * Resource policy (release path, no handle leaks):
 *  - The statistics callback is registered once in `init` and holds no per-session
 *    reference; completed `FFmpegSession` objects are not retained beyond the callback.
 *  - Coroutine cancellation calls `FFmpegKit.cancel(sessionId)` via `invokeOnCancellation`;
 *    [cancelActive] exposes the same cancel handle for workers that cancel outside the
 *    coroutine scope.
 *  - Chunk files created by the Whisper splitter are deleted in a `finally` block; this
 *    processor never holds file contents in memory — FFmpeg reads/writes streams, and
 *    large outputs are produced by the segment muxer, not by whole-file buffering.
 *  - ML Kit translator clients are closed in `finally` (`MlKitTranslator`); no native
 *    client is cached for translation.
 *
 * Progress: a global statistics callback updates [progressPercent] and
 * [lastEncodingTimeMs]. The maintained API reports `Statistics.getTime()` as double
 * seconds (the retired 6.0 API reported long milliseconds); converted here. Exact
 * 0–100% requires probing output duration (not done); the 5–95% ramp is an honest
 * coarse estimate, 100% on success.
 */
@Singleton
class FFmpegProcessorImpl @Inject constructor(
    private val dispatchers: DispatcherProvider,
) : FFmpegProcessor {
    /** Coarse progress 0-100 for the currently running (or last completed) operation. */
    @Volatile var progressPercent: Int = 0
        private set
    /** Last reported encoding position in milliseconds (from FFmpeg statistics). */
    @Volatile var lastEncodingTimeMs: Long = 0L
        private set
    /** Last typed failure for inspection by callers that cast to this concrete type. */
    @Volatile var lastError: FFmpegError? = null
        private set

    private val activeSessionId = AtomicLong(-1L)

    init {
        // Maintained fork 8.1.9 reports Statistics.getTime() as double seconds (retired
        // Arthenica 6.x reported long milliseconds); convert to ms for [lastEncodingTimeMs].
        FFmpegKitConfig.enableStatisticsCallback { stats: Statistics ->
            lastEncodingTimeMs = (stats.time * 1000.0).toLong()
            // Coarse ramp: assume a typical 10-minute (600 s) encode; cap at 95 until
            // completion. Honest limitation: without output-duration probing this is an
            // estimate, not exact.
            val estimated = ((stats.time / 600.0) * 100).toInt().coerceIn(5, 95)
            progressPercent = estimated
        }
    }

    override suspend fun extractAudioToMp3(videoPath: String, outputPath: String): AppResult<Unit> =
        execute(FFmpegJob.extractMp3(videoPath, outputPath))

    /**
     * Soft-embed selectable subtitle track. Legacy contract: `mov_text` into an MP4
     * output (EmbedWorker finalizes to `.mp4`). New callers should use the
     * container-aware overload below (MP4→mov_text, MKV→srt/ass, WebM→webvtt).
     */
    override suspend fun embedSoftSubtitles(
        videoPath: String,
        subtitlePath: String,
        language: String?,
        outputPath: String,
    ): AppResult<Unit> = embedSoftSubtitles(
        videoPath, subtitlePath, language, outputPath,
        targetContainer = "mp4", subtitleFormat = "srt",
    )

    override suspend fun embedSoftSubtitles(
        videoPath: String,
        subtitlePath: String,
        language: String?,
        outputPath: String,
        targetContainer: String,
        subtitleFormat: String,
    ): AppResult<Unit> {
        val ext = targetContainer.trim().lowercase().removePrefix(".")
        val codec = FFmpegArgs.softSubtitleCodecFor(ext, subtitleFormat)
        if (codec == null) {
            val err = FFmpegError.InvalidInput(
                "Container '$targetContainer' has no supported soft-subtitle codec; use hard burn or a sidecar file.",
            )
            lastError = err
            return AppResult.Error(err.message, recoverable = err.recoverable)
        }
        if (!File(videoPath).exists()) {
            val err = FFmpegError.InvalidInput("Video file not found: $videoPath")
            lastError = err
            return AppResult.Error(err.message, recoverable = err.recoverable)
        }
        if (!File(subtitlePath).exists()) {
            val err = FFmpegError.InvalidInput("Subtitle file not found: $subtitlePath")
            lastError = err
            return AppResult.Error(err.message, recoverable = err.recoverable)
        }
        return execute(
            FFmpegJob.softEmbed(videoPath, subtitlePath, outputPath, language, ext, subtitleFormat),
        )
    }

    override suspend fun burnHardSubtitles(
        videoPath: String,
        subtitlePath: String,
        style: EmbedStyle,
        outputPath: String,
    ): AppResult<Unit> = execute(
        FFmpegJob.hardBurn(videoPath, subtitlePath, outputPath, style),
        style = style,
    )

    override suspend fun muxVideoAudio(videoPath: String, audioPath: String, outputPath: String): AppResult<Unit> =
        execute(FFmpegJob.mux(videoPath, audioPath, outputPath))

    override suspend fun remuxContainer(
        inputPath: String,
        outputPath: String,
        targetContainer: String,
    ): AppResult<Unit> {
        val ext = targetContainer.trim().lowercase().removePrefix(".")
        if (ext !in ALLOWED_REMUX_CONTAINERS) {
            val err = FFmpegError.InvalidInput("Unsupported remux container '$targetContainer' (allowed: ${ALLOWED_REMUX_CONTAINERS.joinToString()}).")
            lastError = err
            return AppResult.Error(err.message, recoverable = err.recoverable)
        }
        if (!File(inputPath).exists()) {
            val err = FFmpegError.InvalidInput("Input file not found: $inputPath")
            lastError = err
            return AppResult.Error(err.message, recoverable = err.recoverable)
        }
        return execute(FFmpegJob.remux(inputPath, outputPath, ext))
    }

    override suspend fun splitByTimeSegment(
        inputPath: String,
        outputPattern: String,
        segmentSeconds: Long,
    ): AppResult<Unit> {
        if ("%d" !in outputPattern && "%03d" !in outputPattern) {
            val err = FFmpegError.InvalidInput("Segment output pattern must contain a %d placeholder (e.g. chunk_%03d.mp3).")
            lastError = err
            return AppResult.Error(err.message, recoverable = err.recoverable)
        }
        if (segmentSeconds <= 0L) {
            val err = FFmpegError.InvalidInput("Segment duration must be positive (got $segmentSeconds s).")
            lastError = err
            return AppResult.Error(err.message, recoverable = err.recoverable)
        }
        if (!File(inputPath).exists()) {
            val err = FFmpegError.InvalidInput("Input file not found: $inputPath")
            lastError = err
            return AppResult.Error(err.message, recoverable = err.recoverable)
        }
        return execute(FFmpegJob.splitSegments(inputPath, outputPattern, segmentSeconds))
    }

    /**
     * Cancel the session currently held by this processor, if any. Safe to call from any
     * thread; a no-op when idle. Mirrors the cancellation performed by
     * `invokeOnCancellation` on the job coroutine.
     */
    fun cancelActive() {
        val id = activeSessionId.get()
        if (id >= 0) runCatching { FFmpegKit.cancel(id) }
    }

    // ---------------------------------------------------------------- execution

    private suspend fun execute(job: FFmpegJob, style: EmbedStyle? = null): AppResult<Unit> {
        precheckStorage(job)?.let { err ->
            lastError = err
            return AppResult.Error(err.message, recoverable = err.recoverable)
        }
        val args = FFmpegJobPlanner.vector(job, style)
        progressPercent = 0
        lastEncodingTimeMs = 0L
        lastError = null
        return try {
            withContext(dispatchers.io) {
                suspendCancellableCoroutine { cont ->
                    val session = try {
                        FFmpegKit.executeWithArgumentsAsync(args.toTypedArray()) { completed ->
                            activeSessionId.set(-1L)
                            if (ReturnCode.isSuccess(completed.returnCode)) {
                                progressPercent = 100
                                if (cont.isActive) cont.resume(Unit)
                            } else {
                                progressPercent = 0
                                val code = completed.returnCode?.value
                                val log = lastLogLine(completed)
                                if (cont.isActive) cont.resumeWithException(FFmpegExitException(code, log))
                            }
                        }
                    } catch (t: Throwable) {
                        // Native load failure (missing ABI/.so, unpack error): surface as
                        // NativeUnavailable instead of crashing the coroutine builder.
                        if (cont.isActive) cont.resumeWithException(t)
                        return@suspendCancellableCoroutine
                    }
                    activeSessionId.set(session.sessionId)
                    cont.invokeOnCancellation {
                        runCatching { FFmpegKit.cancel(session.sessionId) }
                        activeSessionId.set(-1L)
                    }
                }
            }
            // Output verification: for non-segment jobs, the output file must exist and
            // be non-empty. A zero-byte or missing output despite a zero exit code is a
            // failure (e.g. empty stream, muxer rejected the codec) and must not surface
            // as success. Segment outputs (pattern with %d) are checked by the caller.
            if (!job.output.contains("%")) {
                val out = File(job.output)
                if (!out.exists() || out.length() == 0L) {
                    val err = FFmpegError.NonZeroExit(
                        exitCode = 0,
                        lastLog = "Output file missing or empty after FFmpeg success: ${out.name}",
                    )
                    lastError = err
                    return AppResult.Error(err.message, recoverable = err.recoverable)
                }
            }
            AppResult.Success(Unit)
        } catch (ce: CancellationException) {
            // Structured cancellation must propagate; never convert it into an error result.
            activeSessionId.set(-1L)
            throw ce
        } catch (exit: FFmpegExitException) {
            val err = FFmpegError.NonZeroExit(exit.code, exit.log)
            lastError = err
            AppResult.Error(err.message, recoverable = err.recoverable)
        } catch (t: Throwable) {
            val err = FFmpegError.fromThrowable(t)
            lastError = err
            AppResult.Error(err.message, recoverable = err.recoverable)
        }
    }

    /**
     * Pure-checked storage pre-step: the estimate ([StorageEstimate]) is compared against
     * usable bytes of the output parent directory. If the size cannot be queried the check
     * is skipped (fail-open on unknown) rather than blocking a legitimate job.
     */
    private fun precheckStorage(job: FFmpegJob): FFmpegError.InsufficientStorage? {
        val outFile = File(job.output.substringBefore('%'))
        val dir = outFile.parentFile ?: return null
        val available = runCatching { dir.usableSpace }.getOrDefault(-1L)
        if (available < 0L) return null
        val sourceBytes = job.inputs.mapNotNull { path ->
            runCatching { File(path).length() }.getOrNull()?.let { path to it }
        }.toMap()
        val estimated = StorageEstimate.estimate(job, sourceBytes)
        if (estimated <= 0L) return null
        if (StorageEstimate.fits(available, estimated)) return null
        return FFmpegError.InsufficientStorage(estimated, available)
    }

    /** Sanitized final non-blank FFmpeg log line (basenames only); formatted by [FFmpegError.NonZeroExit]. */
    private fun lastLogLine(session: com.arthenica.ffmpegkit.FFmpegSession): String {
        val logs = session.allLogsAsString
        return logs?.lines()
            ?.filter { it.isNotBlank() }
            ?.lastOrNull()
            ?.let(::sanitizeLogPaths)
            .orEmpty()
            .ifBlank { "no log output captured" }
    }

    /**
     * Replaces absolute file paths in FFmpeg log lines with their basenames, so a failure
     * message only surfaces the app-controlled temp file name, never the directory layout
     * or an external media path embedded in a log token.
     */
    private fun sanitizeLogPaths(line: String): String =
        line.split(' ').joinToString(" ") { token ->
            val inner = token.trim('\'', '"')
            if (inner.startsWith("/") && inner.contains('.')) {
                val base = inner.substringAfterLast('/')
                when {
                    token.startsWith("'") -> "'$base'"
                    token.startsWith("\"") -> "\"$base\""
                    else -> base
                }
            } else {
                token
            }
        }

    private class FFmpegExitException(val code: Int?, val log: String) : RuntimeException(log)

    private companion object {
        val ALLOWED_REMUX_CONTAINERS = setOf("mp4", "mkv", "webm", "mov", "m4a", "mp3")
    }
}
