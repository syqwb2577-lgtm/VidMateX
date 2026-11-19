package app.vidmatex.feature.subtitles.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [TranscriptionEnqueuePlanner] — the pre-flight validation and
 * deterministic id/name derivation for the public enqueue API. No WorkManager, no
 * Android Context: only the pure planning seam is exercised.
 *
 * Status: COMPLETE_NOT_RUN.
 */
class TranscriptionEnqueuerTest {
    @Test
    fun `missing endpoint surfaces required config and does not plan to enqueue`() {
        val plan = TranscriptionEnqueuePlanner.plan(
            TranscriptionEnqueueConfig(
                localMediaPath = "/storage/video.mp4",
                whisperEndpoint = "",
            ),
        )
        assertTrue(plan is EnqueuePlan.NotConfigured)
        val missing = (plan as EnqueuePlan.NotConfigured).missing
        assertTrue(missing.contains(TranscriptionEnqueuePlanner.MISSING_ENDPOINT))
    }

    @Test
    fun `invalid non-loopback http endpoint is not configured`() {
        val plan = TranscriptionEnqueuePlanner.plan(
            TranscriptionEnqueueConfig(
                localMediaPath = "/v.mp4",
                whisperEndpoint = "http://example.com/whisper",
            ),
        )
        assertTrue(plan is EnqueuePlan.NotConfigured)
    }

    @Test
    fun `blank model surfaces missing model config`() {
        val plan = TranscriptionEnqueuePlanner.plan(
            TranscriptionEnqueueConfig(
                localMediaPath = "/v.mp4",
                whisperEndpoint = "https://host/v1",
                model = "  ",
            ),
        )
        assertTrue(plan is EnqueuePlan.NotConfigured)
        assertTrue((plan as EnqueuePlan.NotConfigured).missing.contains(TranscriptionEnqueuePlanner.MISSING_MODEL))
    }

    @Test
    fun `blank path is invalid input`() {
        val plan = TranscriptionEnqueuePlanner.plan(
            TranscriptionEnqueueConfig(localMediaPath = "  ", whisperEndpoint = "https://host/v1"),
        )
        assertTrue(plan is EnqueuePlan.InvalidInput)
    }

    @Test
    fun `valid config produces ready plan with deterministic ids`() {
        val plan = TranscriptionEnqueuePlanner.plan(
            TranscriptionEnqueueConfig(
                localMediaPath = "/storage/Movies/clip.mp4",
                whisperEndpoint = "https://whisper.example.com/v1",
                sourceLang = "ar",
                requireWifi = true,
            ),
        )
        assertTrue(plan is EnqueuePlan.Ready)
        val ready = plan as EnqueuePlan.Ready
        assertEquals("/storage/Movies/clip.mp4", ready.audioPath)
        assertEquals("ar", ready.sourceLang)
        assertTrue(ready.dedupKey.isNotBlank())
        assertTrue(ready.uniqueWorkName.startsWith(TranscriptionEnqueuer.UNIQUE_WORK_NAME_PREFIX))
        assertTrue(ready.uniqueWorkName.endsWith(ready.dedupKey))
        // Job id auto-derived from dedup key.
        assertTrue(ready.jobId.startsWith("auto_"))
        // Deterministic: same path -> same dedup key, unique name, work id.
        val plan2 = TranscriptionEnqueuePlanner.plan(
            TranscriptionEnqueueConfig(
                localMediaPath = "/storage/Movies/clip.mp4",
                whisperEndpoint = "https://whisper.example.com/v1",
            ),
        ) as EnqueuePlan.Ready
        assertEquals(ready.dedupKey, plan2.dedupKey)
        assertEquals(ready.uniqueWorkName, plan2.uniqueWorkName)
        assertEquals(ready.workId, plan2.workId)
    }

    @Test
    fun `different paths produce different dedup keys and unique names`() {
        val a = TranscriptionEnqueuePlanner.plan(
            TranscriptionEnqueueConfig(localMediaPath = "/a.mp4", whisperEndpoint = "https://h/v1"),
        ) as EnqueuePlan.Ready
        val b = TranscriptionEnqueuePlanner.plan(
            TranscriptionEnqueueConfig(localMediaPath = "/b.mp4", whisperEndpoint = "https://h/v1"),
        ) as EnqueuePlan.Ready
        assertFalse(a.dedupKey == b.dedupKey)
        assertFalse(a.uniqueWorkName == b.uniqueWorkName)
    }

    @Test
    fun `path normalization makes equivalent paths collide`() {
        // normalizePath collapses repeated slashes and trims trailing slashes;
        // /./ segments are NOT resolved (documented limitation), so use // and
        // trailing-slash variants which are guaranteed to collide.
        val a = TranscriptionEnqueuePlanner.plan(
            TranscriptionEnqueueConfig(localMediaPath = "/storage//Movies/clip.mp4", whisperEndpoint = "https://h/v1"),
        ) as EnqueuePlan.Ready
        val b = TranscriptionEnqueuePlanner.plan(
            TranscriptionEnqueueConfig(localMediaPath = "/storage/Movies/clip.mp4/", whisperEndpoint = "https://h/v1"),
        ) as EnqueuePlan.Ready
        assertEquals(a.dedupKey, b.dedupKey)
    }

    @Test
    fun `loopback http dev endpoint is accepted`() {
        val plan = TranscriptionEnqueuePlanner.plan(
            TranscriptionEnqueueConfig(localMediaPath = "/v.mp4", whisperEndpoint = "http://127.0.0.1:8000/v1"),
        )
        assertTrue(plan is EnqueuePlan.Ready)
    }

    @Test
    fun `explicit job id overrides auto derived one`() {
        val plan = TranscriptionEnqueuePlanner.plan(
            TranscriptionEnqueueConfig(
                localMediaPath = "/v.mp4",
                whisperEndpoint = "https://h/v1",
                jobId = "my-job-123",
            ),
        ) as EnqueuePlan.Ready
        assertEquals("my-job-123", plan.jobId)
    }
}
