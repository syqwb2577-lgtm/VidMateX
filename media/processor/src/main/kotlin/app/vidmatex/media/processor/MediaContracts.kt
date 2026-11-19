package app.vidmatex.media.processor

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.model.EmbedStyle
import app.vidmatex.core.model.SubtitleCue
import app.vidmatex.core.model.SubtitleFormat
import app.vidmatex.core.model.TranslationProvider

/** Parse/write SRT, VTT, ASS. Timestamps in ms preserved exactly. */
interface SubtitleParser {
    fun parse(format: SubtitleFormat, text: String): AppResult<List<SubtitleCue>>
    fun write(format: SubtitleFormat, cues: List<SubtitleCue>): String
    fun convert(text: String, from: SubtitleFormat, to: SubtitleFormat): AppResult<String>

    /** Apply a constant offset (milliseconds) to every cue's start/end timestamps. */
    fun shift(cues: List<SubtitleCue>, offsetMs: Long): List<SubtitleCue>

    /**
     * Best-effort language detection from cue text. Returns an ISO-639-1 code or null
     * when undetermined. Heuristic only: Arabic Unicode block => "ar", otherwise null.
     * Callers should prefer an explicit language code when known.
     */
    fun detectLanguage(cues: List<SubtitleCue>): String?
}

/** Translation preserves cue index and timestamps; only text lines change. */
interface SubtitleTranslator {
    val provider: TranslationProvider
    fun isConfigured(): Boolean
    /** Batching and rate-limiting are the implementation's responsibility. */
    suspend fun translate(cues: List<SubtitleCue>, sourceLang: String?, targetLang: String): AppResult<List<SubtitleCue>>
}

/**
 * Speech-to-text integration point. Implementations are either on-device (future) or
 * API-based (e.g. a self-hosted Whisper endpoint — see WhisperSpeechToText). The
 * default binding is NoOpSpeechToText; Whisper is OPTIONAL and only active when the
 * user configures an endpoint in Settings. Never ships a hardcoded key or endpoint.
 */
interface SpeechToText {
    /** False when no endpoint is configured (NoOp) or the endpoint is invalid. */
    fun isAvailable(): Boolean
    /** Transcribe an audio file to cues. sourceLang may be null (auto-detect). */
    suspend fun transcribe(audioPath: String, sourceLang: String?): AppResult<List<SubtitleCue>>
}

/**
 * FFmpeg operations. Implementations MUST build arguments via [FFmpegArgs] (allowlisted
 * tokens) — never concatenate user input into a shell string. Paths are app-controlled.
 *
 * Pure job model, storage pre-check and typed errors live in [FFmpegJob],
 * [StorageEstimate] and [FFmpegError] (JVM-testable); this interface only declares the
 * suspend, device-bound operations.
 */
interface FFmpegProcessor {
    suspend fun extractAudioToMp3(videoPath: String, outputPath: String): AppResult<Unit>
    /** Soft: add subtitle as a selectable mov_text track in an MP4 container. */
    suspend fun embedSoftSubtitles(videoPath: String, subtitlePath: String, language: String?, outputPath: String): AppResult<Unit>

    /**
     * Soft: add subtitle as a selectable track, choosing the codec by [targetContainer]
     * (mp4→mov_text, mkv→srt/ass, webm→webvtt) and [subtitleFormat] ("srt"/"vtt"/"ass";
     * drives MKV ass vs srt). The caller must convert the subtitle file to a format the
     * chosen codec accepts (WebM requires VTT input) before calling.
     */
    suspend fun embedSoftSubtitles(
        videoPath: String,
        subtitlePath: String,
        language: String?,
        outputPath: String,
        targetContainer: String,
        subtitleFormat: String,
    ): AppResult<Unit>
    /** Hard: burn subtitles onto frames with the given style. */
    suspend fun burnHardSubtitles(videoPath: String, subtitlePath: String, style: EmbedStyle, outputPath: String): AppResult<Unit>
    /** Mux separate video+audio streams into one MP4 (for DASH-style separate tracks). */
    suspend fun muxVideoAudio(videoPath: String, audioPath: String, outputPath: String): AppResult<Unit>
    /**
     * Stream-copy remux into another container (e.g. MKV/WebM from MP4); no re-encode.
     * [targetContainer] is one of the allowlisted extensions (`mp4`, `mkv`, `webm`,
     * `mov`, `m4a`, `mp3`); unsupported values map to
     * [app.vidmatex.core.common.AppResult.Error] without invoking FFmpeg.
     */
    suspend fun remuxContainer(inputPath: String, outputPath: String, targetContainer: String): AppResult<Unit>
    /**
     * Split [inputPath] into segments of [segmentSeconds] seconds via the segment muxer;
     * [outputPattern] must contain a `%03d`-style placeholder. Used for large-file,
     * stream-safe processing; the whole file is never buffered in memory.
     */
    suspend fun splitByTimeSegment(inputPath: String, outputPattern: String, segmentSeconds: Long): AppResult<Unit>
}
