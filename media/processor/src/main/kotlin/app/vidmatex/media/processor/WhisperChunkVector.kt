package app.vidmatex.media.processor

/**
 * Pure builder for the Whisper large-file chunk vector (JVM-testable; no Android or
 * FFmpegKit imports). Tokens mirror the production call in
 * [WhisperSpeechToText.splitIfNeeded] so `FFmpegArgsTest`/`WhisperChunkVectorTest` can
 * assert the exact allowlisted vector without native execution.
 *
 * Contract: re-encode to 16 kHz mono MP3 (Whisper-friendly), drop video, use the segment
 * muxer with [segmentSeconds] parts. The output pattern (`chunk_%03d.mp3`) is an
 * app-controlled cache path; user media content never enters the vector as a string.
 */
internal object WhisperChunkVector {
    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val BITRATE = "64k"

    fun build(inputPath: String, outputPattern: String, segmentSeconds: Long): List<String> = listOf(
        "-y",
        "-i", inputPath,
        "-vn",
        "-acodec", "libmp3lame", "-b:a", BITRATE,
        "-ar", SAMPLE_RATE.toString(), "-ac", CHANNELS.toString(),
        "-f", "segment", "-segment_time", segmentSeconds.coerceAtLeast(1L).toString(),
        outputPattern,
    )
}
