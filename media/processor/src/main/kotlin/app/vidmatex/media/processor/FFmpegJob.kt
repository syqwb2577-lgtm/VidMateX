package app.vidmatex.media.processor

import app.vidmatex.core.model.EmbedStyle

/**
 * Pure (Android- and FFmpegKit-free) job model, vector planner, storage estimator and
 * typed error model for [FFmpegProcessor]. Everything here runs on the plain JVM and is
 * exercised by `FFmpegJobModelTest`; the native `FFmpegKit` calls live only in
 * [FFmpegProcessorImpl] and are documented in `docs/FFMPEG.md`.
 */

/** One supported processing operation; each maps to exactly one allowlisted vector. */
enum class FFmpegOperation {
    /** Demux video, encode audio to MP3 (libmp3lame). */
    EXTRACT_AUDIO_MP3,

    /** Attach a selectable subtitle stream (mov_text / webvtt / srt) into the container. */
    SOFT_EMBED_SUBTITLES,

    /** Hard-render SRT/ASS subtitles onto frames (libass + x264). */
    HARD_BURN_SUBTITLES,

    /** Mux separate video and audio streams into one container (`-c copy`). */
    MUX_AV,

    /** Stream-copy remux into a different container (no re-encode). */
    REMUX_CONTAINER,

    /** Split into fixed-duration segments with the segment muxer. */
    SPLIT_SEGMENTS,
}

/**
 * Tunable options carried by a job. Every field has a safe default; numeric quality
 * fields are clamped by the builder before reaching FFmpeg. No secrets, URLs or
 * endpoint values ever enter this model.
 */
data class FFmpegJobOptions(
    /** Hard-burn video encoder (allowlisted by [FFmpegProcessorImpl]; default libx264). */
    val burnCodec: String = "libx264",
    /** x264 CRF quality 0..51 (builder clamps); 18 = visually near-lossless. */
    val crf: Int = 18,
    /** x264 preset (allowlisted enum). */
    val preset: FFmpegArgs.Preset = FFmpegArgs.Preset.MEDIUM,
    /** MP3 extraction bitrate in kbps. */
    val audioBitrateKbps: Int = 192,
    /** Segment length in seconds for [FFmpegOperation.SPLIT_SEGMENTS]. */
    val segmentSeconds: Long = 600L,
    /** Soft-subtitle codec override; null = derive from target container. */
    val softSubtitleCodec: String? = null,
    /** ISO language tag written as `language=` metadata on a soft track. */
    val languageTag: String? = null,
    /** Target container extension for remux/soft-embed outputs. */
    val targetContainer: String = "mp4",
)

/**
 * Declarative description of one processing job: what to read, what to do, what to write.
 * Paths are app-controlled file paths (pickers/cache dirs); the planner turns the job
 * into a `List<String>` argument vector — never a shell string.
 */
data class FFmpegJob(
    val operation: FFmpegOperation,
    val inputs: List<String>,
    val output: String,
    val options: FFmpegJobOptions = FFmpegJobOptions(),
) {
    val primaryInput: String get() = inputs.firstOrNull().orEmpty()

    companion object {
        fun extractMp3(videoPath: String, outputPath: String, bitrateKbps: Int = 192) =
            FFmpegJob(
                FFmpegOperation.EXTRACT_AUDIO_MP3,
                listOf(videoPath),
                outputPath,
                FFmpegJobOptions(audioBitrateKbps = bitrateKbps),
            )

        fun softEmbed(
            videoPath: String,
            subtitlePath: String,
            outputPath: String,
            languageTag: String?,
            targetContainer: String,
            /** Subtitle format of [subtitlePath] ("srt"/"vtt"/"ass"); drives MKV codec choice. */
            subtitleFormat: String = "srt",
        ) = FFmpegJob(
            FFmpegOperation.SOFT_EMBED_SUBTITLES,
            listOf(videoPath, subtitlePath),
            outputPath,
            FFmpegJobOptions(
                softSubtitleCodec = FFmpegArgs.softSubtitleCodecFor(targetContainer, subtitleFormat),
                languageTag = languageTag,
                targetContainer = targetContainer,
            ),
        )

        fun hardBurn(videoPath: String, subtitlePath: String, outputPath: String, style: EmbedStyle) =
            FFmpegJob(FFmpegOperation.HARD_BURN_SUBTITLES, listOf(videoPath, subtitlePath), outputPath)

        fun mux(videoPath: String, audioPath: String, outputPath: String) =
            FFmpegJob(FFmpegOperation.MUX_AV, listOf(videoPath, audioPath), outputPath)

        fun remux(inputPath: String, outputPath: String, targetContainer: String) =
            FFmpegJob(
                FFmpegOperation.REMUX_CONTAINER,
                listOf(inputPath),
                outputPath,
                FFmpegJobOptions(targetContainer = targetContainer),
            )

        fun splitSegments(inputPath: String, outputPattern: String, segmentSeconds: Long) =
            FFmpegJob(
                FFmpegOperation.SPLIT_SEGMENTS,
                listOf(inputPath),
                outputPattern,
                FFmpegJobOptions(segmentSeconds = segmentSeconds),
            )
    }
}

/**
 * Pure planner: [FFmpegJob] → `List<String>` tokens through [FFmpegArgs]. The production
 * implementation delegates vector construction here so the tested pure seam and the
 * native call site can never drift apart. [style] is required for hard burn;
 * [subtitlePath] for soft embed (present in [FFmpegJob.inputs] as the secondary input).
 */
internal object FFmpegJobPlanner {
    fun vector(job: FFmpegJob, style: EmbedStyle? = null): List<String> {
        val b = FFmpegArgs().overwrite().logLevel(FFmpegArgs.LogLevel.ERROR)
        return when (job.operation) {
            FFmpegOperation.EXTRACT_AUDIO_MP3 -> b
                .input(job.primaryInput)
                .noVideo()
                .audioCodec("libmp3lame")
                .audioBitrate(job.options.audioBitrateKbps)
                .output(job.output)
                .build()

            FFmpegOperation.SOFT_EMBED_SUBTITLES -> {
                val video = job.inputs.getOrNull(0).orEmpty()
                val subtitle = job.inputs.getOrNull(1).orEmpty()
                val codec = job.options.softSubtitleCodec ?: "mov_text"
                b.input(video).input(subtitle)
                    .map("0").map("1")
                    .videoCodec("copy").audioCodec("copy")
                    .subtitleCodec(codec)
                // +faststart is an MP4/mov flag; WebM/MKV soft embeds must not emit it.
                if (codec == "mov_text") b.movFlags("+faststart")
                job.options.languageTag?.trim()?.takeIf { it.isNotBlank() }?.let {
                    b.metadataLanguage("s:0", it.lowercase().take(3))
                }
                b.output(job.output).build()
            }

            FFmpegOperation.HARD_BURN_SUBTITLES -> {
                val video = job.inputs.getOrNull(0).orEmpty()
                val subtitle = job.inputs.getOrNull(1).orEmpty()
                b.input(video)
                    .subtitlesFilter(subtitle, style ?: EmbedStyle())
                    .videoCodec(job.options.burnCodec)
                    .crf(job.options.crf)
                    .preset(job.options.preset)
                    .audioCodec("copy")
                    .output(job.output)
                    .build()
            }

            FFmpegOperation.MUX_AV -> b
                .input(job.inputs.getOrNull(0).orEmpty())
                .input(job.inputs.getOrNull(1).orEmpty())
                .map("0:v").map("1:a")
                .videoCodec("copy").audioCodec("copy")
                .shortest()
                .output(job.output)
                .build()

            FFmpegOperation.REMUX_CONTAINER -> b
                .input(job.primaryInput)
                .copyCodecs()
                .format(FFmpegArgs.muxerForContainer(job.options.targetContainer))
                .output(job.output)
                .build()

            FFmpegOperation.SPLIT_SEGMENTS -> b
                .input(job.primaryInput)
                .copyCodecs()
                .segment(job.options.segmentSeconds, job.output)
                .build()
        }
    }
}

/**
 * Pure insufficient-storage pre-check. No Android `StatFs`/`usableSpace` call lives here;
 * the implementation queries the device filesystem and feeds bytes in. Estimates are
 * conservative ceiling figures used only to fail fast before a long encode starts.
 */
object StorageEstimate {
    /** Safety margin reserved on top of the estimate (temp files, mux overhead, MP3 frames). */
    const val DEFAULT_SLACK_BYTES: Long = 64L * 1024 * 1024
    private const val BYTES_PER_KBIT = 1000.0 / 8.0

    /**
     * True when [availableBytes] covers [estimatedBytes] plus [slack]. Negative or
     * missing inputs fail closed (false) rather than assuming space exists.
     */
    fun fits(availableBytes: Long, estimatedBytes: Long, slack: Long = DEFAULT_SLACK_BYTES): Boolean {
        if (availableBytes < 0 || estimatedBytes < 0 || slack < 0) return false
        return availableBytes >= estimatedBytes + slack
    }

    /** MP3 size: seconds × bitrate; ceil to whole bytes. */
    fun mp3Bytes(durationMs: Long, bitrateKbps: Int): Long {
        if (durationMs <= 0 || bitrateKbps <= 0) return 0L
        return Math.ceil(durationMs / 1000.0 * bitrateKbps * BYTES_PER_KBIT).toLong()
    }

    /** Stream-copy remux/mux: output ≈ total input bytes (container overhead only). */
    fun copyBytes(vararg sourceBytes: Long): Long = sourceBytes.filter { it > 0 }.sum()

    /**
     * Hard burn re-encode: conservative ceiling = 1.1× the largest input plus a 1 MiB
     * floor; x264 CRF 18 output is usually smaller, but the pre-check must not reject a
     * feasible job on an underestimate.
     */
    fun burnedBytes(vararg sourceBytes: Long): Long {
        val largest = sourceBytes.filter { it > 0 }.maxOrNull() ?: 0L
        return (largest * 1.1).toLong().coerceAtLeast(1L * 1024 * 1024)
    }

    /** Segmented output: total bytes ≈ the source (parts sum to the whole stream). */
    fun segmentBytes(sourceBytes: Long): Long = if (sourceBytes > 0) sourceBytes else 0L

    /** Dispatch estimate for a job; [sourceBytes] maps input path → known size (may be absent). */
    fun estimate(job: FFmpegJob, sourceBytes: Map<String, Long>): Long {
        val sizes = job.inputs.mapNotNull { sourceBytes[it] }
        return when (job.operation) {
            FFmpegOperation.EXTRACT_AUDIO_MP3 -> {
                // Duration unknown without a probe; use 0.9× source as a conservative
                // upper bound (audio-only MP3 never exceeds the video source bytes).
                (sizes.firstOrNull()?.let { (it * 0.9).toLong() } ?: 0L)
            }
            FFmpegOperation.SOFT_EMBED_SUBTITLES,
            FFmpegOperation.MUX_AV,
            FFmpegOperation.REMUX_CONTAINER -> copyBytes(*sizes.toLongArray())
            FFmpegOperation.HARD_BURN_SUBTITLES -> burnedBytes(*sizes.toLongArray())
            FFmpegOperation.SPLIT_SEGMENTS -> segmentBytes(sizes.firstOrNull() ?: 0L)
        }
    }
}

/**
 * Typed failure surface for FFmpeg jobs. The native layer maps low-level outcomes
 * (exit codes, linkage errors, cancellation, storage) into these; UI/worker code can
 * pattern-match without parsing log strings. Pure data; no FFmpegKit references.
 */
sealed class FFmpegError(open val message: String, val recoverable: Boolean) {
    /** Non-zero FFmpeg exit code; [lastLog] is the sanitized final log line. */
    data class NonZeroExit(val exitCode: Int?, val lastLog: String) :
        FFmpegError("FFmpeg failed (exit code $exitCode): $lastLog", recoverable = true)

    /** Native library could not be loaded (missing ABI on 32-bit devices, unpack failure). */
    data class NativeUnavailable(val detail: String) :
        FFmpegError("FFmpeg native library unavailable on this device: $detail", recoverable = false)

    /** Coroutine/job cancellation propagated through the cancel handle. */
    object Cancelled : FFmpegError("FFmpeg operation was cancelled.", recoverable = true)

    /** Free space below the conservative estimate plus slack. */
    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) :
        FFmpegError(
            "Insufficient storage: need ~$requiredBytes bytes, available $availableBytes bytes.",
            recoverable = true,
        )

    /** Rejected input (missing file, unsupported container, bad option). */
    data class InvalidInput(val reason: String) :
        FFmpegError("Invalid media input: $reason", recoverable = true)

    /** Any other unexpected failure. */
    data class UnknownFailure(val detail: String) :
        FFmpegError("Unexpected FFmpeg failure: $detail", recoverable = false)

    companion object {
        /** Map an FFmpegKit return code plus sanitized log line. */
        fun fromReturnCode(exitCode: Int?, lastLog: String): FFmpegError =
            NonZeroExit(exitCode, lastLog.ifBlank { "no log output captured" })

        /**
         * Map a thrown failure. Linkage/initialization errors (missing `.so` / ABI)
         * become [NativeUnavailable]; everything else is [UnknownFailure]. Cancellation
         * is handled separately by the coroutine machinery and must not land here.
         */
        fun fromThrowable(t: Throwable): FFmpegError = when (t) {
            is UnsatisfiedLinkError, is NoClassDefFoundError, is ExceptionInInitializerError ->
                NativeUnavailable(t.message ?: t.javaClass.simpleName)
            else -> UnknownFailure(t.message ?: t.javaClass.simpleName)
        }
    }
}
