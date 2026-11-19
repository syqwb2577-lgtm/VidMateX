package app.vidmatex.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.vidmatex.core.model.DownloadState
import app.vidmatex.core.model.SourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented DAO tests for [DownloadDao]. IMPLEMENTED BUT NOT VERIFIED: requires a
 * device/emulator; compile-ready. Run with ./gradlew :core:database:connectedDebugAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class DownloadDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DownloadDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.downloadDao()
    }

    @After
    fun teardown() = db.close()

    private fun sample(id: String = "d1", state: DownloadState = DownloadState.QUEUED) = DownloadEntity(
        id = id,
        sourceUrl = "https://example.com/v.mp4",
        sourceType = SourceType.DIRECT_URL,
        title = "Video",
        state = state,
        specJson = "{}",
        qualityLabel = "720p",
        trackKind = "video",
    )

    @Test
    fun upsertAndObserveById() = runTest {
        dao.upsert(sample())
        val loaded = dao.getById("d1")
        assertNotNull(loaded)
        assertEquals("Video", loaded!!.title)
        assertFalse(loaded.orphaned)
        val observed = dao.observeById("d1").first()
        assertEquals("d1", observed?.id)
    }

    @Test
    fun upsertReplacesOnConflict() = runTest {
        dao.upsert(sample().copy(title = "First"))
        dao.upsert(sample().copy(title = "Second"))
        assertEquals("Second", dao.getById("d1")!!.title)
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun markCompletedSetsStateAndFilePath() = runTest {
        dao.upsert(sample(state = DownloadState.ACTIVE))
        dao.markCompleted("d1", filePath = "/data/v.mp4", subtitlePath = "/data/v.srt")
        val loaded = dao.getById("d1")!!
        assertEquals(DownloadState.COMPLETED, loaded.state)
        assertEquals(100, loaded.progressPercent)
        assertEquals("/data/v.mp4", loaded.filePath)
        assertEquals("/data/v.srt", loaded.subtitlePath)
        assertNull(loaded.errorMessage)
    }

    @Test
    fun markFailedSetsStateAndMessage() = runTest {
        dao.upsert(sample(state = DownloadState.ACTIVE, id = "f1"))
        dao.markFailed("f1", "timeout")
        val loaded = dao.getById("f1")!!
        assertEquals(DownloadState.FAILED, loaded.state)
        assertEquals("timeout", loaded.errorMessage)
        assertEquals(0L, loaded.speedBps)
    }

    @Test
    fun kindAwareDedupTreatsNullTrackKindAsVideo() = runTest {
        dao.upsert(sample(id = "v1", state = DownloadState.ACTIVE).copy(trackKind = null))
        val active = dao.getActiveByUrlQualityAndKind(
            "https://example.com/v.mp4", "720p", "video",
        )
        assertEquals(1, active.size)
        val audio = dao.getActiveByUrlQualityAndKind(
            "https://example.com/v.mp4", "720p", "audio",
        )
        assertTrue(audio.isEmpty())
    }

    @Test
    fun setOrphanedFlagPersistsAndObserveOrphansListsIt() = runTest {
        dao.upsert(sample(id = "o1", state = DownloadState.COMPLETED))
        dao.setOrphaned("o1", true)
        assertTrue(dao.getById("o1")!!.orphaned)
        val orphans = dao.getOrphans()
        assertEquals(1, orphans.size)
        assertEquals("o1", orphans[0].id)
        val observed = dao.observeOrphans().first()
        assertEquals(1, observed.size)
    }

    @Test
    fun resetInterruptedActiveJobsMovesActiveToQueued() = runTest {
        dao.upsert(sample(id = "a1", state = DownloadState.ACTIVE).copy(speedBps = 5000))
        dao.upsert(sample(id = "q1", state = DownloadState.QUEUED))
        dao.upsert(sample(id = "c1", state = DownloadState.COMPLETED))
        val reset = dao.resetInterruptedActiveJobs()
        assertEquals(1, reset)
        assertEquals(DownloadState.QUEUED, dao.getById("a1")!!.state)
        assertEquals(0L, dao.getById("a1")!!.speedBps)
        assertEquals(DownloadState.QUEUED, dao.getById("q1")!!.state) // untouched
        assertEquals(DownloadState.COMPLETED, dao.getById("c1")!!.state) // untouched
    }

    @Test
    fun observeInFlightReturnsQueuedAndActiveOnly() = runTest {
        dao.upsert(sample(id = "q", state = DownloadState.QUEUED))
        dao.upsert(sample(id = "a", state = DownloadState.ACTIVE))
        dao.upsert(sample(id = "f", state = DownloadState.FAILED))
        val inFlight = dao.observeInFlight().first()
        assertEquals(2, inFlight.size)
        assertTrue(inFlight.any { it.id == "q" })
        assertTrue(inFlight.any { it.id == "a" })
    }

    @Test
    fun clearHistoryRemovesFinishedRowsOnly() = runTest {
        dao.upsert(sample(id = "c", state = DownloadState.COMPLETED))
        dao.upsert(sample(id = "f", state = DownloadState.FAILED))
        dao.upsert(sample(id = "x", state = DownloadState.CANCELLED))
        dao.upsert(sample(id = "q", state = DownloadState.QUEUED))
        dao.clearHistory()
        assertNull(dao.getById("c"))
        assertNull(dao.getById("f"))
        assertNotNull(dao.getById("q"))
    }

    @Test
    fun updateProgressPersistsAllFields() = runTest {
        dao.upsert(sample())
        dao.updateProgress("d1", progressPercent = 42, speedBps = 1000, etaSec = 30,
            totalBytes = 100_000L, downloadedBytes = 42_000L)
        val loaded = dao.getById("d1")!!
        assertEquals(42, loaded.progressPercent)
        assertEquals(1000L, loaded.speedBps)
        assertEquals(30L, loaded.etaSec)
        assertEquals(100_000L, loaded.totalBytes)
        assertEquals(42_000L, loaded.downloadedBytes)
    }

    @Test
    fun updateResumablePersists() = runTest {
        dao.upsert(sample())
        dao.updateResumable("d1", true)
        assertTrue(dao.getById("d1")!!.resumable)
    }
}
