package app.vidmatex.feature.downloads

import app.vidmatex.core.model.DownloadMode
import app.vidmatex.core.model.DownloadSpec
import app.vidmatex.core.model.MediaKind
import app.vidmatex.core.model.MediaTrack
import app.vidmatex.core.model.SourceType
import app.vidmatex.core.model.VideoMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Additional pure-JVM coverage for the download policy objects that were only
 * partially exercised by [DownloadWorkerHelpersTest]:
 * [DownloadKinds.primaryKind]/[kindFor] extension edges, [DedupPolicy.guardKey]
 * composition, [ResumePolicy] 2xx-with-probe branch, and [RetryPolicy]
 * classification labels for permanent device/format/resolver failures.
 *
 * No Android/WorkManager/OkHttp types are referenced.
 * Status: source-ready pending CI (JDK 17 + `./gradlew testDebugUnitTest`);
 * NOT executed in this environment (no Android SDK / Gradle; host JDK 11).
 */
class DownloadPoliciesExtraTest {

    private val audioTrack = MediaTrack(trackId = "a1", kind = MediaKind.AUDIO, container = "m4a")
    private val videoTrack = MediaTrack(trackId = "v1", kind = MediaKind.VIDEO, container = "mp4", heightPx = 720)

    private fun spec(
        mode: DownloadMode = DownloadMode.VIDEO_ONLY,
        video: MediaTrack? = videoTrack,
        audio: MediaTrack? = audioTrack,
    ): DownloadSpec {
        val metadata = VideoMetadata(
            sourceType = SourceType.DIRECT_URL,
            sourceUrl = "https://example.com/v.mp4",
            sourceId = "id",
            title = "Title",
        )
        return DownloadSpec(
            metadata = metadata,
            mode = mode,
            videoTrack = video,
            audioTrack = audio,
            outputFileName = "title",
        )
    }

    // ------------------------------------------------------- DownloadKinds

    @Test
    fun `primaryKind is audio only for structural audio-only specs`() {
        assertEquals(DownloadKinds.KIND_AUDIO, DownloadKinds.primaryKind(null, audioTrack))
        assertEquals(DownloadKinds.KIND_VIDEO, DownloadKinds.primaryKind(videoTrack, audioTrack))
        assertEquals(DownloadKinds.KIND_VIDEO, DownloadKinds.primaryKind(null, null))
    }

    @Test
    fun `kindFor maps subtitle mode audio flag and structural pair`() {
        assertEquals(DownloadKinds.KIND_SUBTITLE, DownloadKinds.kindFor(DownloadMode.SUBTITLE_ONLY, false, null, null))
        assertEquals(DownloadKinds.KIND_AUDIO, DownloadKinds.kindFor(DownloadMode.AUDIO_ONLY, false, null, audioTrack))
        assertEquals(DownloadKinds.KIND_AUDIO, DownloadKinds.kindFor(DownloadMode.VIDEO_ONLY, true, null, audioTrack))
        assertEquals(DownloadKinds.KIND_VIDEO, DownloadKinds.kindFor(DownloadMode.VIDEO_ONLY, false, videoTrack, audioTrack))
    }

    @Test
    fun `kindFor structural audio-only old spec without flag falls back to primaryKind`() {
        // Legacy serialized spec: VIDEO_ONLY mode, flag false, videoTrack null, audioTrack set.
        assertEquals(DownloadKinds.KIND_AUDIO, DownloadKinds.kindFor(DownloadMode.VIDEO_ONLY, false, null, audioTrack))
        assertEquals(DownloadKinds.KIND_VIDEO, DownloadKinds.kindFor(DownloadMode.VIDEO_ONLY, false, null, null))
        assertTrue(DownloadKinds.isAudioOnly(null, audioTrack))
        assertFalse(DownloadKinds.isAudioOnly(videoTrack, null))
        assertFalse(DownloadKinds.isAudioOnly(null, null))
    }

    @Test
    fun `audioExtension covers remaining containers and unknown truncation`() {
        assertEquals("webm", DownloadKinds.audioExtension("webm"))
        assertEquals("ogg", DownloadKinds.audioExtension("ogg"))
        assertEquals("ogg", DownloadKinds.audioExtension("OGG"))
        assertEquals("m4a", DownloadKinds.audioExtension("mp4a"))
        assertEquals("m4a", DownloadKinds.audioExtension(""))
        assertEquals("avi", DownloadKinds.audioExtension("avi"))
        assertEquals("webm", DownloadKinds.audioExtension("webm-hd-1080p"))
    }

    // ------------------------------------------------------- DedupPolicy

    @Test
    fun `guardKey combines url label and audio parity`() {
        val audioSpec = spec(mode = DownloadMode.VIDEO_ONLY, video = null, audio = audioTrack)
        val videoSpec = spec()
        assertEquals("https://example.com/v.mp4|M4A|true", DedupPolicy.guardKey("https://example.com/v.mp4", audioSpec))
        assertEquals("https://example.com/v.mp4|720p|false", DedupPolicy.guardKey("https://example.com/v.mp4", videoSpec))
    }

    @Test
    fun `qualityLabel falls back to subtitle or default`() {
        assertEquals("subtitle", DedupPolicy.qualityLabel(spec(mode = DownloadMode.SUBTITLE_ONLY, video = null, audio = null)))
        assertEquals("default", DedupPolicy.qualityLabel(spec(mode = DownloadMode.VIDEO_ONLY, video = null, audio = null)))
    }

    // ------------------------------------------------------- ResumePolicy

    @Test
    fun `2xx with bytes header appends, without bytes header restarts`() {
        assertEquals(
            ResumePolicy.Decision.APPEND,
            ResumePolicy.decide(40_000L, 204, "Accept-Ranges: bytes"),
        )
        assertEquals(
            ResumePolicy.Decision.RESTART_FROM_ZERO,
            ResumePolicy.decide(40_000L, 204, "none"),
        )
        assertEquals(
            ResumePolicy.Decision.UNSUPPORTED_RANGE,
            ResumePolicy.decide(40_000L, 503, null),
        )
        assertFalse(ResumePolicy.appends(ResumePolicy.Decision.FRESH_GET))
        assertFalse(ResumePolicy.appends(ResumePolicy.Decision.RESTART_FROM_ZERO))
        assertFalse(ResumePolicy.appends(ResumePolicy.Decision.UNSUPPORTED_RANGE))
        assertTrue(ResumePolicy.appends(ResumePolicy.Decision.APPEND))
    }

    // ------------------------------------------------------- RetryPolicy

    @Test
    fun `permanent device format and resolver failures classify and do not retry`() {
        assertEquals(RetryPolicy.Kind.STORAGE, RetryPolicy.classify(Exception("No space left on device (ENOSPC)")))
        assertEquals(RetryPolicy.Kind.PERMISSION, RetryPolicy.classify(Exception("Permission denied by SAF picker")))
        assertEquals(RetryPolicy.Kind.UNSUPPORTED_FORMAT, RetryPolicy.classify(Exception("Unsupported container for burn-in")))
        assertEquals(RetryPolicy.Kind.RESOLVER, RetryPolicy.classify(Exception("Could not be resolved by upstream")))
        assertEquals(RetryPolicy.Kind.UNKNOWN, RetryPolicy.classify(Exception("completely unrelated failure")))

        assertFalse(RetryPolicy.shouldRetry(RetryPolicy.classify(Exception("ENOSPC: storage full"))))
        assertFalse(RetryPolicy.shouldRetry(RetryPolicy.classify(Exception("permission denied"))))
        assertFalse(RetryPolicy.shouldRetry(RetryPolicy.classify(Exception("not supported format"))))
        assertFalse(RetryPolicy.shouldRetry(RetryPolicy.classify(Exception("resolver error"))))
        assertFalse(RetryPolicy.shouldRetry(RetryPolicy.classify(Exception("unknown boom"))))
    }
}
