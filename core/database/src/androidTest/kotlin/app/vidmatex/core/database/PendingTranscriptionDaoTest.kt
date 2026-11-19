package app.vidmatex.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented DAO tests for [PendingTranscriptionDao]. Requires an Android device/emulator
 * and CANNOT run in this environment; written compile-ready and correct per Room's contract.
 * Run with: ./gradlew :core:database:connectedDebugAndroidTest
 *
 * Covers: insertPendingIfAbsent idempotency (UNIQUE dedupKey + INSERT OR IGNORE),
 * findPending filtering, markEnqueued/markSucceeded/markFailed/markNotTranscribable
 * terminal transitions, and resetToPending (cancellation retry).
 */
@RunWith(AndroidJUnit4::class)
class PendingTranscriptionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PendingTranscriptionDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.pendingTranscriptionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertPendingIfAbsent_insertsNewRowAndReturnsTrue() = runBlocking {
        val inserted = dao.insertPendingIfAbsent(
            downloadId = "d1", filePath = "/sdcard/v.mp4", subtitlePath = null,
            title = "Video", sourceUrl = "https://example.com/v",
        )
        assertTrue(inserted)
        val pending = dao.findPending()
        assertEquals(1, pending.size)
        assertEquals("d1", pending[0].downloadId)
        assertEquals(PendingTranscriptionStatus.PENDING, pending[0].status)
        assertEquals(0, pending[0].attempts)
    }

    @Test
    fun insertPendingIfAbsent_sameFileTwice_insertsOnceAndSecondReturnsFalse() = runBlocking {
        val first = dao.insertPendingIfAbsent("d1", "/sdcard/v.mp4", null, "V", "https://e/v")
        val second = dao.insertPendingIfAbsent("d2", "/sdcard/v.mp4", null, "V", "https://e/v")
        assertTrue(first)
        assertFalse("second insert for same dedupKey must be ignored", second)
        assertEquals(1, dao.findPending().size)
        assertEquals("d1", dao.findPending()[0].downloadId) // first insert wins
    }

    @Test
    fun insertPendingIfAbsent_differentFiles_bothInserted() = runBlocking {
        dao.insertPendingIfAbsent("d1", "/sdcard/a.mp4", null, "A", "https://e/a")
        dao.insertPendingIfAbsent("d2", "/sdcard/b.mp4", null, "B", "https://e/b")
        assertEquals(2, dao.findPending().size)
    }

    @Test
    fun insertPendingIfAbsent_blankFilePath_returnsFalseAndInsertsNothing() = runBlocking {
        val inserted = dao.insertPendingIfAbsent("d1", "   ", null, "V", "https://e/v")
        assertFalse(inserted)
        assertTrue(dao.findPending().isEmpty())
    }

    @Test
    fun findPending_returnsOnlyPendingRowsOrderedByCreatedAt() = runBlocking {
        dao.insertPendingIfAbsent("d1", "/sdcard/a.mp4", null, "A", "https://e/a")
        dao.insertPendingIfAbsent("d2", "/sdcard/b.mp4", null, "B", "https://e/b")
        val keyA = PendingTranscriptionKeys.keyFor("/sdcard/a.mp4")
        dao.markSucceeded(keyA, 1000L) // terminal — must not appear in findPending
        val pending = dao.findPending()
        assertEquals(1, pending.size)
        assertEquals("d2", pending[0].downloadId)
    }

    @Test
    fun markEnqueued_transitionsPendingToEnqueuedAndIncrementsAttempts() = runBlocking {
        dao.insertPendingIfAbsent("d1", "/sdcard/v.mp4", null, "V", "https://e/v")
        val key = PendingTranscriptionKeys.keyFor("/sdcard/v.mp4")
        val updated = dao.markEnqueued(key, 2000L)
        assertEquals(1, updated)
        assertTrue(dao.findPending().isEmpty()) // no longer PENDING
        val row = dao.findByDedupKey(key)
        assertNotNull(row)
        assertEquals(PendingTranscriptionStatus.ENQUEUED, row!!.status)
        assertEquals(1, row.attempts)
    }

    @Test
    fun markEnqueued_doesNotTouchAlreadyTerminalRow() = runBlocking {
        dao.insertPendingIfAbsent("d1", "/sdcard/v.mp4", null, "V", "https://e/v")
        val key = PendingTranscriptionKeys.keyFor("/sdcard/v.mp4")
        dao.markSucceeded(key, 1000L)
        val updated = dao.markEnqueued(key, 2000L) // guarded by status='PENDING'
        assertEquals(0, updated)
        assertEquals(PendingTranscriptionStatus.SUCCEEDED, dao.findByDedupKey(key)!!.status)
    }

    @Test
    fun markSucceeded_isTerminalAndClearsErrorMessage() = runBlocking {
        dao.insertPendingIfAbsent("d1", "/sdcard/v.mp4", null, "V", "https://e/v")
        val key = PendingTranscriptionKeys.keyFor("/sdcard/v.mp4")
        dao.markFailed(key, "boom", 1000L)
        dao.markSucceeded(key, 2000L) // success after failure (e.g. retry) clears error
        val row = dao.findByDedupKey(key)!!
        assertEquals(PendingTranscriptionStatus.SUCCEEDED, row.status)
        // errorMessage should be cleared by markSucceeded.
        assertTrue(row.errorMessage == null || row.errorMessage.isEmpty())
        assertTrue(dao.findPending().isEmpty())
    }

    @Test
    fun markFailed_recordsErrorMessageAndIsTerminal() = runBlocking {
        dao.insertPendingIfAbsent("d1", "/sdcard/v.mp4", null, "V", "https://e/v")
        val key = PendingTranscriptionKeys.keyFor("/sdcard/v.mp4")
        dao.markFailed(key, "network timeout", 1000L)
        val row = dao.findByDedupKey(key)!!
        assertEquals(PendingTranscriptionStatus.FAILED, row.status)
        assertEquals("network timeout", row.errorMessage)
        assertTrue(dao.findPending().isEmpty())
    }

    @Test
    fun markNotTranscribable_transitionsToCancelled() = runBlocking {
        dao.insertPendingIfAbsent("d1", "/sdcard/sub.srt", null, "S", "https://e/s")
        val key = PendingTranscriptionKeys.keyFor("/sdcard/sub.srt")
        dao.markNotTranscribable(key, "not_audio_video", 1000L)
        val row = dao.findByDedupKey(key)!!
        assertEquals(PendingTranscriptionStatus.CANCELLED, row.status)
        assertEquals("not_audio_video", row.errorMessage)
        assertTrue(dao.findPending().isEmpty())
    }

    @Test
    fun resetToPending_revivesEnqueuedRowForRetry() = runBlocking {
        dao.insertPendingIfAbsent("d1", "/sdcard/v.mp4", null, "V", "https://e/v")
        val key = PendingTranscriptionKeys.keyFor("/sdcard/v.mp4")
        dao.markEnqueued(key, 1000L)
        assertTrue(dao.findPending().isEmpty())
        val updated = dao.resetToPending(key, 2000L) // worker cancelled → retryable
        assertEquals(1, updated)
        assertEquals(1, dao.findPending().size)
        assertEquals(PendingTranscriptionStatus.PENDING, dao.findByDedupKey(key)!!.status)
    }

    @Test
    fun resetToPending_doesNotReviveTerminalRow() = runBlocking {
        dao.insertPendingIfAbsent("d1", "/sdcard/v.mp4", null, "V", "https://e/v")
        val key = PendingTranscriptionKeys.keyFor("/sdcard/v.mp4")
        dao.markSucceeded(key, 1000L)
        val updated = dao.resetToPending(key, 2000L) // guarded by status='ENQUEUED'
        assertEquals(0, updated)
        assertEquals(PendingTranscriptionStatus.SUCCEEDED, dao.findByDedupKey(key)!!.status)
        assertTrue(dao.findPending().isEmpty())
    }
}
