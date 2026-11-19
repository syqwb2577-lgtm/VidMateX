package app.vidmatex.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM (no Room/Android) tests for the pending-transcription state machine, entity
 * defaults, and dedup-key computation. DAO SQL execution requires an instrumented run
 * (see PendingTranscriptionDaoTest in src/androidTest).
 */
class PendingTranscriptionStateMachineTest {
    @Test fun `status constants are stable strings`() {
        assertEquals("PENDING", PendingTranscriptionStatus.PENDING)
        assertEquals("ENQUEUED", PendingTranscriptionStatus.ENQUEUED)
        assertEquals("SUCCEEDED", PendingTranscriptionStatus.SUCCEEDED)
        assertEquals("FAILED", PendingTranscriptionStatus.FAILED)
        assertEquals("CANCELLED", PendingTranscriptionStatus.CANCELLED)
    }

    @Test fun `terminal statuses are SUCCEEDED FAILED CANCELLED`() {
        assertTrue(PendingTranscriptionStatus.isTerminal(PendingTranscriptionStatus.SUCCEEDED))
        assertTrue(PendingTranscriptionStatus.isTerminal(PendingTranscriptionStatus.FAILED))
        assertTrue(PendingTranscriptionStatus.isTerminal(PendingTranscriptionStatus.CANCELLED))
        assertFalse(PendingTranscriptionStatus.isTerminal(PendingTranscriptionStatus.PENDING))
        assertFalse(PendingTranscriptionStatus.isTerminal(PendingTranscriptionStatus.ENQUEUED))
    }

    @Test fun `only PENDING is retryable (returned by findPending)`() {
        assertTrue(PendingTranscriptionStatus.isRetryable(PendingTranscriptionStatus.PENDING))
        assertFalse(PendingTranscriptionStatus.isRetryable(PendingTranscriptionStatus.ENQUEUED))
        assertFalse(PendingTranscriptionStatus.isRetryable(PendingTranscriptionStatus.SUCCEEDED))
        assertFalse(PendingTranscriptionStatus.isRetryable(PendingTranscriptionStatus.FAILED))
        assertFalse(PendingTranscriptionStatus.isRetryable(PendingTranscriptionStatus.CANCELLED))
    }

    @Test fun `entity defaults to PENDING with zero attempts and null errors`() {
        val entity = PendingTranscriptionEntity(
            id = "p1",
            downloadId = "d1",
            filePath = "/sdcard/Movies/v.mp4",
            title = "Video",
            sourceUrl = "https://example.com/v",
            dedupKey = "abc123",
        )
        assertEquals(PendingTranscriptionStatus.PENDING, entity.status)
        assertEquals(0, entity.attempts)
        assertNull(entity.subtitlePath)
        assertNull(entity.errorMessage)
        assertTrue(entity.createdAtMs > 0)
        assertTrue(entity.updatedAtMs > 0)
    }

    @Test fun `entity copy preserves id and dedupKey and updates status`() {
        val entity = PendingTranscriptionEntity(
            id = "p1", downloadId = "d1", filePath = "/a.mp4",
            title = "T", sourceUrl = "https://e", dedupKey = "k1",
        )
        val enqueued = entity.copy(status = PendingTranscriptionStatus.ENQUEUED, attempts = 1)
        assertEquals("p1", enqueued.id)
        assertEquals("k1", enqueued.dedupKey)
        assertEquals(PendingTranscriptionStatus.ENQUEUED, enqueued.status)
        assertEquals(1, enqueued.attempts)
    }

    @Test fun `keyFor blank input returns blank (no dedup, insert skipped)`() {
        assertEquals("", PendingTranscriptionKeys.keyFor(""))
        assertEquals("", PendingTranscriptionKeys.keyFor("   "))
    }

    @Test fun `keyFor is deterministic and 64 hex chars`() {
        val a = PendingTranscriptionKeys.keyFor("/sdcard/Movies/video.mp4")
        val b = PendingTranscriptionKeys.keyFor("/sdcard/Movies/video.mp4")
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test fun `keyFor different paths produce different keys`() {
        assertNotEquals(
            PendingTranscriptionKeys.keyFor("/a.mp4"),
            PendingTranscriptionKeys.keyFor("/b.mp4"),
        )
    }

    @Test fun `normalize collapses backslashes double slashes and trailing slashes`() {
        assertEquals("/a/b/c", PendingTranscriptionKeys.normalize("\\a\\b\\c\\"))
        assertEquals("/a/b/c", PendingTranscriptionKeys.normalize("/a//b///c/"))
        assertEquals("/a/b/c", PendingTranscriptionKeys.normalize("  /a/b/c/  "))
    }

    @Test fun `equivalent normalized paths collide on keyFor`() {
        assertEquals(
            PendingTranscriptionKeys.keyFor("/a/b/c.mp4"),
            PendingTranscriptionKeys.keyFor("\\a\\b\\c.mp4\\"),
        )
        assertEquals(
            PendingTranscriptionKeys.keyFor("/a/b/c.mp4"),
            PendingTranscriptionKeys.keyFor("/a//b/c.mp4/"),
        )
    }
}
