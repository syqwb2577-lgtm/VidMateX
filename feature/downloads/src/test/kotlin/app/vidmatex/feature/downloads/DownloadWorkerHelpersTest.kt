package app.vidmatex.feature.downloads

import app.vidmatex.core.model.DownloadMode
import app.vidmatex.core.model.DownloadSpec
import app.vidmatex.core.model.MediaKind
import app.vidmatex.core.model.MediaTrack
import app.vidmatex.core.model.SourceType
import app.vidmatex.core.model.VideoMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException

/**
 * Pure-function tests for [DownloadWorker] companion helpers and the worker-side pure
 * policies (resume decision, retry classification, audio-only spec helpers, dedup key).
 * No Android/WorkManager/OkHttp imports.
 *
 * Written but NOT executed (no Android SDK / JDK 17 in this environment).
 * Status: COMPLETE_NOT_RUN — pending CI (`./gradlew :feature:downloads:testDebugUnitTest`).
 */
class DownloadWorkerHelpersTest {
    // ------------------------------------------------------------ existing worker companions
    @Test
    fun `parseContentRangeTotal extracts total after slash`() {
        assertEquals(12345L, DownloadWorker.parseContentRangeTotal("bytes 0-1023/12345"))
        assertEquals(100L, DownloadWorker.parseContentRangeTotal("bytes 500-999/100"))
    }
    @Test
    fun `parseContentRangeTotal returns null for blank or asterisk`() {
        assertNull(DownloadWorker.parseContentRangeTotal(null))
        assertNull(DownloadWorker.parseContentRangeTotal(""))
        assertNull(DownloadWorker.parseContentRangeTotal("bytes 0-1023/*"))
    }
    @Test
    fun `sumOrNull adds when both present`() {
        assertEquals(30L, DownloadWorker.sumOrNull(10L, 20L))
        assertEquals(10L, DownloadWorker.sumOrNull(10L, null))
        assertEquals(20L, DownloadWorker.sumOrNull(null, 20L))
        assertNull(DownloadWorker.sumOrNull(null, null))
    }
    @Test
    fun `sanitizeFileName strips unsafe chars`() {
        assertEquals("my_video_file", DownloadWorker.sanitizeFileName("my video:file?"))
        // Blank/whitespace-only titles collapse to the safe fallback "download".
        assertEquals("download", DownloadWorker.sanitizeFileName("   "))
        assertEquals("a_b_c", DownloadWorker.sanitizeFileName("a/b\\c"))
    }
    @Test
    fun `sanitizeFileName truncates to 120 chars`() {
        val long = "a".repeat(200)
        val result = DownloadWorker.sanitizeFileName(long)
        assertEquals(120, result.length)
    }
    @Test
    fun `sanitizeFileName keeps safe extension and collapses unsafe separators`() {
        assertEquals("My_Video_2024.mp4", DownloadWorker.sanitizeFileName("My Video (2024).mp4"))
        assertEquals("download", DownloadWorker.sanitizeFileName("///"))
    }

    // ------------------------------------------------------------ ResumePolicy (task 2)
    private val audioTrack = MediaTrack(
        trackId = "a1", kind = MediaKind.AUDIO, container = "m4a",
    )
    private val videoTrack = MediaTrack(
        trackId = "v1", kind = MediaKind.VIDEO, container = "mp4", heightPx = 720,
    )

    @Test
    fun `resume- 206 with partial bytes appends`() {
        assertEquals(
            ResumePolicy.Decision.APPEND,
            ResumePolicy.decide(40_000L, 206, "bytes"),
        )
        assertTrue(ResumePolicy.appends(ResumePolicy.decide(40_000L, 206, "bytes")))
    }
    @Test
    fun `resume- 200 with partial bytes restarts from zero and never appends`() {
        assertEquals(
            ResumePolicy.Decision.RESTART_FROM_ZERO,
            ResumePolicy.decide(40_000L, 200, "bytes"),
        )
        assertFalse(ResumePolicy.appends(ResumePolicy.decide(40_000L, 200, "bytes")))
    }
    @Test
    fun `resume- 416 stale partial restarts from zero`() {
        assertEquals(
            ResumePolicy.Decision.RESTART_FROM_ZERO,
            ResumePolicy.decide(40_000L, 416, null),
        )
    }
    @Test
    fun `resume- no partial bytes always starts fresh`() {
        assertEquals(ResumePolicy.Decision.FRESH_GET, ResumePolicy.decide(0L, 200, null))
        assertEquals(ResumePolicy.Decision.FRESH_GET, ResumePolicy.decide(0L, 206, "bytes"))
        assertEquals(ResumePolicy.Decision.FRESH_GET, ResumePolicy.decide(-5L, 200, null))
    }
    @Test
    fun `resume- non-range success codes are unsupported`() {
        assertEquals(ResumePolicy.Decision.UNSUPPORTED_RANGE, ResumePolicy.decide(40_000L, 403, null))
        assertEquals(ResumePolicy.Decision.UNSUPPORTED_RANGE, ResumePolicy.decide(40_000L, 500, null))
    }
    @Test
    fun `probeAllowed honors track flag and probe header`() {
        assertTrue(ResumePolicy.probeAllowed(trackResumable = true, acceptRangesHeader = null))
        assertTrue(ResumePolicy.probeAllowed(trackResumable = false, acceptRangesHeader = "bytes"))
        assertFalse(ResumePolicy.probeAllowed(trackResumable = false, acceptRangesHeader = "none"))
        assertFalse(ResumePolicy.probeAllowed(trackResumable = null, acceptRangesHeader = null))
        assertTrue(ResumePolicy.probeAllowed(trackResumable = null, acceptRangesHeader = "Accept-Ranges: bytes"))
    }
    @Test
    fun `acceptRangesAllowed parses bytes header case-insensitively`() {
        assertTrue(ResumePolicy.acceptRangesAllowed("bytes"))
        assertTrue(ResumePolicy.acceptRangesAllowed("BYTES"))
        assertFalse(ResumePolicy.acceptRangesAllowed("none"))
        assertFalse(ResumePolicy.acceptRangesAllowed(null))
    }

    // ------------------------------------------------------------ RetryPolicy (task 5)
    @Test
    fun `retry- IOException timeout and 5xx 429 are retryable`() {
        assertTrue(RetryPolicy.shouldRetry(RetryPolicy.classify(IOException("connect"))))
        assertTrue(RetryPolicy.shouldRetry(RetryPolicy.classify(SocketTimeoutException("timeout"))))
        assertTrue(RetryPolicy.shouldRetry(RetryPolicy.classify(IOException("boom"), 503)))
        assertTrue(RetryPolicy.shouldRetry(RetryPolicy.classify(RuntimeException("rate"), 429)))
        assertTrue(RetryPolicy.shouldRetry(RetryPolicy.classify(RuntimeException("server"), 500)))
    }
    @Test
    fun `retry- permanent 4xx cancellation and missing inputs are not retryable`() {
        assertFalse(RetryPolicy.shouldRetry(RetryPolicy.classify(RuntimeException("auth"), 401)))
        assertFalse(RetryPolicy.shouldRetry(RetryPolicy.classify(RuntimeException("gone"), 404)))
        assertFalse(RetryPolicy.shouldRetry(RetryPolicy.classify(RuntimeException("forbidden"), 403)))
        assertFalse(RetryPolicy.shouldRetry(RetryPolicy.classify(CancellationException("cancelled"))))
        assertFalse(RetryPolicy.shouldRetry(RetryPolicy.classify(Exception("No stream available for the selected track."))))
        assertFalse(RetryPolicy.shouldRetry(RetryPolicy.classify(Exception("Permission denied by SAF"))))
    }
    @Test
    fun `embed retries are capped at two extra attempts`() {
        assertFalse(RetryPolicy.embedRetryExhausted(0))
        assertFalse(RetryPolicy.embedRetryExhausted(1))
        assertTrue(RetryPolicy.embedRetryExhausted(2))
        assertTrue(RetryPolicy.embedRetryExhausted(3))
    }

    // ------------------------------------------------------------ DownloadKinds audio-only (task 1)
    @Test
    fun `audioOnly detected structurally when videoTrack null and audioTrack set`() {
        assertTrue(DownloadKinds.isAudioOnly(null, audioTrack))
        assertFalse(DownloadKinds.isAudioOnly(videoTrack, null))
        assertFalse(DownloadKinds.isAudioOnly(videoTrack, audioTrack)) // muxed pair
        assertFalse(DownloadKinds.isAudioOnly(null, null))
    }
    @Test
    fun `audioExtension maps containers to final file extensions`() {
        assertEquals("mp3", DownloadKinds.audioExtension("mp3"))
        assertEquals("m4a", DownloadKinds.audioExtension("m4a"))
        assertEquals("m4a", DownloadKinds.audioExtension("aac"))
        assertEquals("ogg", DownloadKinds.audioExtension("opus"))
        assertEquals("wav", DownloadKinds.audioExtension("wav"))
        assertEquals("flac", DownloadKinds.audioExtension("flac"))
        assertEquals("m4a", DownloadKinds.audioExtension(null))
        assertEquals("m4a", DownloadKinds.audioExtension("mp4"))
    }
    @Test
    fun `looksLikeAudioLabel recognizes audio quality labels`() {
        assertTrue(DownloadKinds.looksLikeAudioLabel("MP3"))
        assertTrue(DownloadKinds.looksLikeAudioLabel("M4A"))
        assertTrue(DownloadKinds.looksLikeAudioLabel("OGG"))
        assertFalse(DownloadKinds.looksLikeAudioLabel("720p"))
        assertFalse(DownloadKinds.looksLikeAudioLabel(null))
    }

    // ------------------------------------------------------------ DedupPolicy (task 4)
    private fun spec(audioOnly: Boolean): DownloadSpec {
        val metadata = VideoMetadata(
            sourceType = SourceType.DIRECT_URL,
            sourceUrl = "https://example.com/video.mp4",
            sourceId = "id",
            title = "Title",
        )
        return DownloadSpec(
            metadata = metadata,
            mode = DownloadMode.VIDEO_ONLY,
            videoTrack = if (audioOnly) null else videoTrack,
            audioTrack = audioTrack,
            outputFileName = "title",
        )
    }
    @Test
    fun `dedup- kind parity must match for duplicate rejection`() {
        assertTrue(DedupPolicy.sameKind(existingAudioOnly = true, candidateAudioOnly = true))
        assertTrue(DedupPolicy.sameKind(existingAudioOnly = false, candidateAudioOnly = false))
        assertFalse(DedupPolicy.sameKind(existingAudioOnly = true, candidateAudioOnly = false))
    }
    @Test
    fun `dedup- audio-only spec reports audio quality label`() {
        assertEquals("M4A", DedupPolicy.qualityLabel(spec(audioOnly = true)))
        assertEquals("720p", DedupPolicy.qualityLabel(spec(audioOnly = false)))
    }

    // ------------------------------------------------------------ WorkerInputPolicy
    @Test
    fun `worker input- both id and spec json present parse to inputs`() {
        val inputs = WorkerInputPolicy.parse("item-1", """{"metadata":{}}""")
        requireNotNull(inputs)
        assertEquals("item-1", inputs.itemId)
    }
    @Test
    fun `worker input- missing or blank id or json fails policy`() {
        assertNull(WorkerInputPolicy.parse(null, "{}"))
        assertNull(WorkerInputPolicy.parse(" ", "{}"))
        assertNull(WorkerInputPolicy.parse("item-1", null))
        assertNull(WorkerInputPolicy.parse("item-1", "  "))
    }
    @Test
    fun `worker input- payload shape guard rejects non json blobs`() {
        assertTrue(WorkerInputPolicy.specPayloadLooksValid("""{"a":1}"""))
        assertFalse(WorkerInputPolicy.specPayloadLooksValid("not json"))
        assertFalse(WorkerInputPolicy.specPayloadLooksValid("{}"))
    }

    // ------------------------------------------------------------ DedupPolicy key
    @Test
    fun `dedup key combines normalized url kind and quality`() {
        val key = DedupPolicy.dedupKey("HTTPS://Example.com/Path?utm_source=x", "720p", "video")
        assertEquals("https://example.com/path?utm_source=x|video|720p", key)
    }

    // ------------------------------------------------------------ RangeHeaderPolicy
    @Test
    fun `range header- Accept-Ranges bytes parsed case-insensitively`() {
        assertTrue(RangeHeaderPolicy.acceptsBytes("bytes"))
        assertTrue(RangeHeaderPolicy.acceptsBytes("Accept-Ranges: BYTES"))
        assertFalse(RangeHeaderPolicy.acceptsBytes("none"))
        assertFalse(RangeHeaderPolicy.acceptsBytes(null))
    }
    @Test
    fun `range header- Content-Range total parsed, asterisk and garbage yield null`() {
        assertEquals(12345L, RangeHeaderPolicy.parseContentRangeTotal("bytes 0-1023/12345"))
        assertEquals(100L, RangeHeaderPolicy.parseContentRangeTotal("bytes 500-999/100"))
        assertNull(RangeHeaderPolicy.parseContentRangeTotal("bytes 0-1023/*"))
        assertNull(RangeHeaderPolicy.parseContentRangeTotal(null))
        assertNull(RangeHeaderPolicy.parseContentRangeTotal("garbage"))
    }
    @Test
    fun `range header- resumed total prefers Content-Range then existing plus body length`() {
        assertEquals(
            50_000L,
            RangeHeaderPolicy.totalForResumedResponse("bytes 1000-49999/50000", 1_000L, 49_000L),
        )
        assertEquals(
            11_000L,
            RangeHeaderPolicy.totalForResumedResponse(null, 1_000L, 10_000L),
        )
        assertNull(RangeHeaderPolicy.totalForResumedResponse(null, 1_000L, -1L))
    }
    @Test
    fun `range header- Content-Length parsed and negatives rejected`() {
        assertEquals(2048L, RangeHeaderPolicy.parseContentLength("2048"))
        assertNull(RangeHeaderPolicy.parseContentLength("-1"))
        assertNull(RangeHeaderPolicy.parseContentLength(null))
        assertNull(RangeHeaderPolicy.parseContentLength("abc"))
    }
}
