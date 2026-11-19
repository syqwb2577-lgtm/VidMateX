package app.vidmatex.feature.subtitles.transcription

import app.vidmatex.core.model.SubtitleFormat

/**
 * Pure, Android-free path and suffix helpers for transcription outputs. Kept free of
 * `java.io.File` and Android calls so JVM unit tests can exercise path construction
 * deterministically on any host OS. The worker wraps these strings in `File` instances.
 */
object TranscriptionPaths {
    /** Worker output directory under `filesDir`: `transcriptions/<jobId>/`. */
    const val OUTPUT_ROOT = "transcriptions"

    /** Copied picker files under `cacheDir`: `transcription_in/`. */
    const val INPUT_CACHE_DIR = "transcription_in"

    /** `filesDir/transcriptions/<jobId>` — both SRT and VTT land in this directory. */
    fun outputDir(filesDir: String, jobId: String): String =
        "${filesDir.trimEnd('/')}/$OUTPUT_ROOT/${sanitizeSegment(jobId)}"

    /** `<dir>/<base>.srt` */
    fun srtFile(outputDir: String, baseName: String): String =
        "${outputDir.trimEnd('/')}/${safeBaseName(baseName)}.${suffix(SubtitleFormat.SRT)}"

    /** `<dir>/<base>.vtt` */
    fun vttFile(outputDir: String, baseName: String): String =
        "${outputDir.trimEnd('/')}/${safeBaseName(baseName)}.${suffix(SubtitleFormat.VTT)}"

    /** `cacheDir/transcription_in` — staging copy for files picked via OpenDocument. */
    fun inputCacheDir(cacheDir: String): String = "${cacheDir.trimEnd('/')}/$INPUT_CACHE_DIR"

    /**
     * Subtitle suffix selection per format. The Whisper worker always emits BOTH
     * SRT and VTT; ASS is not produced from speech recognition.
     */
    fun suffix(format: SubtitleFormat): String = when (format) {
        SubtitleFormat.SRT -> "srt"
        SubtitleFormat.VTT -> "vtt"
        SubtitleFormat.ASS -> "ass"
    }

    private fun safeBaseName(name: String): String =
        name.trim()
            .ifBlank { "transcription" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_', '-', '.')
            .ifBlank { "transcription" }

    internal fun sanitizeSegment(segment: String): String =
        segment.trim()
            .replace(Regex("[^A-Za-z0-9_-]+"), "_")
            .trim('_')
            .ifBlank { "job" }
}
