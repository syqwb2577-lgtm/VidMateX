package app.vidmatex.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.vidmatex.core.model.SourceType
import app.vidmatex.core.model.SubtitleKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented DAO tests for [MediaMetadataDao], [SubtitleRecordDao],
 * [PlaybackProgressDao], and [ApiConfigRefDao].
 *
 * IMPLEMENTED BUT NOT VERIFIED: requires a device/emulator; compile-ready.
 * Run with ./gradlew :core:database:connectedDebugAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class LookupDaosTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun teardown() = db.close()

    // --- MediaMetadataDao ---
    @Test
    fun mediaMetadata_upsertAndGet() = runTest {
        val dao = db.mediaMetadataDao()
        dao.upsert(
            MediaMetadataEntity(
                id = "m1", sourceUrl = "https://example.com/v", sourceType = SourceType.YOUTUBE,
                title = "T", updatedAtMs = 100,
            )
        )
        val loaded = dao.getById("m1")
        assertNotNull(loaded)
        assertEquals("T", loaded!!.title)
        assertEquals("video", loaded.kind)
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun mediaMetadata_getBySourceUrlAndPrune() = runTest {
        val dao = db.mediaMetadataDao()
        dao.upsert(MediaMetadataEntity("m1", "https://example.com/a", SourceType.DIRECT_URL, "A", updatedAtMs = 50))
        dao.upsert(MediaMetadataEntity("m2", "https://example.com/a", SourceType.DIRECT_URL, "A2", updatedAtMs = 200))
        val byUrl = dao.getBySourceUrl("https://example.com/a")
        assertEquals(2, byUrl.size)
        dao.pruneOlderThan(100)
        assertEquals(1, dao.getBySourceUrl("https://example.com/a").size)
    }

    // --- SubtitleRecordDao ---
    @Test
    fun subtitleRecord_upsertAndObserveForMedia() = runTest {
        val dao = db.subtitleRecordDao()
        dao.upsert(SubtitleRecordEntity("s1", "m1", "t1", "en", "English", SubtitleKind.OFFICIAL))
        dao.upsert(SubtitleRecordEntity("s2", "m1", "t2", "ar", "Arabic", SubtitleKind.AUTOMATIC))
        val forMedia = dao.listForMedia("m1")
        assertEquals(2, forMedia.size)
        assertEquals(2, dao.observeForMedia("m1").first().size)
    }

    @Test
    fun subtitleRecord_getByMediaAndTrackIdReturnsOne() = runTest {
        val dao = db.subtitleRecordDao()
        dao.upsert(SubtitleRecordEntity("s1", "m1", "t1", "en", "English", SubtitleKind.OFFICIAL))
        val found = dao.getByMediaAndTrackId("m1", "t1")
        assertNotNull(found)
        assertEquals("s1", found!!.id)
        assertNull(dao.getByMediaAndTrackId("m1", "t99"))
    }

    @Test
    fun subtitleRecord_markFetchedSetsPathAndTranslated() = runTest {
        val dao = db.subtitleRecordDao()
        dao.upsert(SubtitleRecordEntity("s1", "m1", "t1", "en", "English", SubtitleKind.OFFICIAL))
        dao.markFetched("s1", filePath = "/data/en.srt", translated = true)
        val loaded = dao.listForMedia("m1")[0]
        assertEquals("/data/en.srt", loaded.filePath)
        assertTrue(loaded.translated)
    }

    @Test
    fun subtitleRecord_uniqueIndexWithReplaceStrategyDedupsByMediaAndTrack() = runTest {
        val dao = db.subtitleRecordDao()
        dao.upsert(SubtitleRecordEntity("s1", "m1", "t1", "en", "English", SubtitleKind.OFFICIAL))
        // OnConflictStrategy.REPLACE applies to the unique index on (mediaId, trackId):
        // inserting "s2" with the same (m1, t1) replaces "s1" (deletes old, inserts new),
        // so at most one row per (mediaId, trackId) ever exists.
        dao.upsert(SubtitleRecordEntity("s2", "m1", "t1", "en", "English", SubtitleKind.OFFICIAL))
        val rows = dao.listForMedia("m1")
        assertEquals(1, rows.size)
        assertEquals("s2", rows[0].id) // newest insert wins
    }

    @Test
    fun subtitleRecord_deleteForMediaAndPrune() = runTest {
        val dao = db.subtitleRecordDao()
        dao.upsert(SubtitleRecordEntity("s1", "m1", "t1", "en", "English", SubtitleKind.OFFICIAL, createdAtMs = 10))
        dao.upsert(SubtitleRecordEntity("s2", "m2", "t1", "en", "English", SubtitleKind.OFFICIAL, createdAtMs = 1000))
        dao.deleteForMedia("m1")
        assertEquals(0, dao.listForMedia("m1").size)
        dao.pruneOlderThan(500)
        assertEquals(0, dao.listForMedia("m2").size)
    }

    // --- PlaybackProgressDao ---
    @Test
    fun playbackProgress_upsertAndObserve() = runTest {
        val dao = db.playbackProgressDao()
        dao.upsert(PlaybackProgressEntity(mediaId = "m1", positionMs = 5000, durationMs = 60000))
        val loaded = dao.getByMediaId("m1")
        assertNotNull(loaded)
        assertEquals(5000L, loaded!!.positionMs)
        assertEquals(60000L, loaded.durationMs)
        assertEquals("m1", dao.observeByMediaId("m1").first()!!.mediaId)
    }

    @Test
    fun playbackProgress_markCompletedSetsFlagAndPosition() = runTest {
        val dao = db.playbackProgressDao()
        dao.upsert(PlaybackProgressEntity(mediaId = "m1", positionMs = 1000, durationMs = 60000))
        dao.markCompleted("m1", ts = 999)
        val loaded = dao.getByMediaId("m1")!!
        assertTrue(loaded.completed)
        assertEquals(60000L, loaded.positionMs)
        assertEquals(999L, loaded.updatedAtMs)
    }

    @Test
    fun playbackProgress_pruneCompletedOlderThan() = runTest {
        val dao = db.playbackProgressDao()
        dao.upsert(PlaybackProgressEntity(mediaId = "old", completed = true, updatedAtMs = 100))
        dao.upsert(PlaybackProgressEntity(mediaId = "new", completed = true, updatedAtMs = 1000))
        dao.pruneCompletedOlderThan(500)
        assertNull(dao.getByMediaId("old"))
        assertNotNull(dao.getByMediaId("new"))
    }

    // --- ApiConfigRefDao ---
    @Test
    fun apiConfigRef_upsertAndMarkTested() = runTest {
        val dao = db.apiConfigRefDao()
        dao.upsert(ApiConfigRefEntity(alias = "resolver", provider = "resolver", baseUrl = "https://r.example.com/"))
        val loaded = dao.getByAlias("resolver")
        assertNotNull(loaded)
        assertEquals("https://r.example.com/", loaded!!.baseUrl)
        dao.markTested("resolver", ts = 12345, status = "ok")
        val tested = dao.getByAlias("resolver")!!
        assertEquals("ok", tested.lastStatus)
        assertEquals(12345L, tested.lastTestedAtMs)
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun apiConfigRef_neverStoresSecretValue() = runTest {
        val dao = db.apiConfigRefDao()
        dao.upsert(ApiConfigRefEntity(alias = "whisper", provider = "whisper", keyAlias = "whisper_api_key_enc"))
        val loaded = dao.getByAlias("whisper")!!
        // keyAlias is an alias into the encrypted store, never a token.
        assertEquals("whisper_api_key_enc", loaded.keyAlias)
        assertEquals("", loaded.lastStatus)
    }
}
