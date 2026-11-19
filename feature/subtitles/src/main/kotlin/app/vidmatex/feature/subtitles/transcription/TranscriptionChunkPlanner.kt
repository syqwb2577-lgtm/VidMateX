package app.vidmatex.feature.subtitles.transcription

/**
 * Orchestration-level chunk manifest for long media (Whisper-compatible servers
 * commonly cap a request near 25 MB; OpenAI's Whisper limit is 25 MB).
 *
 * Split ownership:
 *  - NATIVE SPLITTING (FFmpegKit segment re-encode) lives in `media:processor`
 *    (`WhisperSpeechToText.splitIfNeeded`, 600 s segments, 16 kHz mono MP3). This
 *    planner does not invoke FFmpeg and cannot run natively in this static build.
 *  - THIS PLANNER builds the pure manifest/queue: fixed-duration windows with a small
 *    overlap between consecutive chunks, and a timestamp offset per chunk so cues
 *    transcribed from chunk N are shifted to the source timeline when merged.
 *
 * When the source duration is unknown (the `SpeechToText` interface exposes no
 * duration today), the planner emits a single manifest entry flagged
 * [ChunkManifest.nativeSplitAvailable] = false: the orchestration queues one upload
 * and the media client either chunks natively on device or surfaces the server
 * rejection. That gap is a documented device/blocked item, never a fake success.
 */
data class ChunkManifest(
    val index: Int,
    /** Slice start on the source timeline (ms); includes overlap back-pull. */
    val sliceStartMs: Long,
    /** Slice end on the source timeline (ms). */
    val sliceEndMs: Long,
    /** Offset added to every cue transcribed from this chunk (ms, nominal window start). */
    val offsetMs: Long,
    /** Overlap applied at this slice's start (0 for the first chunk). */
    val overlapMs: Long,
    /** False when native splitting could not be planned (unknown duration / no FFmpeg device). */
    val nativeSplitAvailable: Boolean,
)

object TranscriptionChunkPlanner {
    /** 10-minute fixed segment, matching the media client's `CHUNK_DURATION_SEC`. */
    const val DEFAULT_CHUNK_MS = 600_000L
    /** 2 s cross-chunk overlap so speech cut at a boundary is still capturable. */
    const val DEFAULT_OVERLAP_MS = 2_000L
    const val MAX_SINGLE_UPLOAD_BYTES = 24L * 1024 * 1024

    /**
     * @param durationMs known media duration; null/<=0 when unknown
     * @param chunkMs nominal fixed segment length
     * @param overlapMs overlap pulled into a slice before the nominal boundary
     */
    fun plan(
        durationMs: Long?,
        chunkMs: Long = DEFAULT_CHUNK_MS,
        overlapMs: Long = DEFAULT_OVERLAP_MS,
    ): List<ChunkManifest> {
        if (durationMs == null || durationMs <= 0L) {
            return listOf(
                ChunkManifest(
                    index = 0,
                    sliceStartMs = 0L,
                    sliceEndMs = 0L,
                    offsetMs = 0L,
                    overlapMs = 0L,
                    nativeSplitAvailable = false,
                ),
            )
        }
        val safeOverlap = overlapMs.coerceAtLeast(0L).coerceAtMost(chunkMs / 2)
        val chunks = ArrayList<ChunkManifest>()
        var index = 0
        var start = 0L
        while (start < durationMs) {
            val nominalEnd = (index + 1) * chunkMs
            val sliceStart = if (index == 0) 0L else (index * chunkMs - safeOverlap).coerceAtLeast(0L)
            val sliceEnd = minOf(nominalEnd, durationMs)
            chunks += ChunkManifest(
                index = index,
                sliceStartMs = sliceStart,
                sliceEndMs = sliceEnd,
                offsetMs = index * chunkMs,
                overlapMs = if (index == 0) 0L else safeOverlap,
                nativeSplitAvailable = true,
            )
            index++
            start = nominalEnd
        }
        return chunks
    }

    /** Queue decision: true when the file size exceeds the single-upload ceiling. */
    fun needsChunking(fileBytes: Long): Boolean = fileBytes > MAX_SINGLE_UPLOAD_BYTES
}
