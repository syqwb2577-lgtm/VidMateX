package app.vidmatex.transcription

import app.vidmatex.core.common.SettingsRepository
import app.vidmatex.core.database.PendingTranscriptionDao
import app.vidmatex.core.database.PendingTranscriptionEntity
import app.vidmatex.core.database.PendingTranscriptionStatus
import app.vidmatex.core.model.AppSettings
import app.vidmatex.feature.downloads.CompletedDownloadBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [AutoTranscribeObserver.scanPending] durable pending-scan logic.
 * No Android framework / WorkManager: the enqueuer and path resolver are fakes, the DAO
 * is a hand-written fake recording mutations. Live-event collection is not exercised
 * here (it shares the same [AutoTranscribeObserver.enqueueForRow] path).
 */
class AutoTranscribeObserverTest {

    private class FakeSettingsRepository(private val initialSettings: AppSettings) : SettingsRepository {
        override val settings: Flow<AppSettings> = flowOf(initialSettings)
        override suspend fun update(transform: (AppSettings) -> AppSettings) {}
        override suspend fun current(): AppSettings = initialSettings
    }

    private class FakePendingTranscriptionDao(
        private val pending: List<PendingTranscriptionEntity>,
    ) : PendingTranscriptionDao {
        val enqueuedKeys = mutableListOf<String>()
        val notTranscribable = mutableListOf<Pair<String, String>>()
        override suspend fun insertIgnore(entity: PendingTranscriptionEntity): Long = -1L
        override suspend fun findPending(): List<PendingTranscriptionEntity> = pending
        override suspend fun findByDedupKey(dedupKey: String): PendingTranscriptionEntity? = null
        override suspend fun markEnqueued(dedupKey: String, nowMs: Long): Int {
            enqueuedKeys.add(dedupKey); return 1
        }
        override suspend fun markSucceeded(dedupKey: String, nowMs: Long): Int = 1
        override suspend fun markFailed(dedupKey: String, errorMessage: String?, nowMs: Long): Int = 1
        override suspend fun markNotTranscribable(dedupKey: String, errorMessage: String, nowMs: Long): Int {
            notTranscribable.add(dedupKey to errorMessage); return 1
        }
        override suspend fun resetToPending(dedupKey: String, nowMs: Long): Int = 1
    }

    private class FakeEnqueuer : TranscriptionJobEnqueuer {
        val calls = mutableListOf<String>() // dedupKey per enqueue
        override fun enqueueUnique(audioPath: String, sourceLang: String, requireWifi: Boolean, dedupKey: String) {
            calls.add(dedupKey)
        }
    }

    private class FakePathResolver(private val resolved: String?) : LocalFilePathResolver {
        override fun resolve(filePath: String): String? = resolved
    }

    private fun row(dedupKey: String, filePath: String = "/stored/v.mp4") = PendingTranscriptionEntity(
        id = "id-$dedupKey",
        downloadId = "dl-$dedupKey",
        filePath = filePath,
        title = "T",
        sourceUrl = "https://example.com/v",
        dedupKey = dedupKey,
        status = PendingTranscriptionStatus.PENDING,
    )

    private fun observer(
        settings: AppSettings,
        dao: FakePendingTranscriptionDao,
        enqueuer: TranscriptionJobEnqueuer,
        resolver: LocalFilePathResolver,
    ) = AutoTranscribeObserver(
        completedDownloadBus = CompletedDownloadBus(),
        settingsRepository = FakeSettingsRepository(settings),
        pendingTranscriptionDao = dao,
        enqueuer = enqueuer,
        pathResolver = resolver,
    )

    private val enabledSettings = AppSettings(
        autoTranscribe = true,
        whisperEndpoint = "https://whisper.example.com/transcribe",
        requireWifi = false,
    )

    @Test fun `scanPending enqueues unique work and marks ENQUEUED for a pending audio row`() = runBlocking {
        val dao = FakePendingTranscriptionDao(listOf(row("k1")))
        val enqueuer = FakeEnqueuer()
        val obs = observer(enabledSettings, dao, enqueuer, FakePathResolver("/local/v.mp4"))
        obs.scanPending()
        assertEquals(listOf("k1"), enqueuer.calls)
        assertEquals(listOf("k1"), dao.enqueuedKeys)
        assertTrue(dao.notTranscribable.isEmpty())
    }

    @Test fun `scanPending enqueues every pending row in order`() = runBlocking {
        val dao = FakePendingTranscriptionDao(listOf(row("k1"), row("k2"), row("k3")))
        val enqueuer = FakeEnqueuer()
        val obs = observer(enabledSettings, dao, enqueuer, FakePathResolver("/local/v.mp4"))
        obs.scanPending()
        assertEquals(listOf("k1", "k2", "k3"), enqueuer.calls)
        assertEquals(listOf("k1", "k2", "k3"), dao.enqueuedKeys)
    }

    @Test fun `scanPending does nothing when autoTranscribe is off (rows left PENDING)`() = runBlocking {
        val dao = FakePendingTranscriptionDao(listOf(row("k1")))
        val enqueuer = FakeEnqueuer()
        val obs = observer(
            AppSettings(autoTranscribe = false, whisperEndpoint = "https://w"),
            dao, enqueuer, FakePathResolver("/local/v.mp4"),
        )
        obs.scanPending()
        assertTrue(enqueuer.calls.isEmpty())
        assertTrue(dao.enqueuedKeys.isEmpty())
        assertTrue(dao.notTranscribable.isEmpty())
    }

    @Test fun `scanPending does nothing when whisper endpoint is unconfigured`() = runBlocking {
        val dao = FakePendingTranscriptionDao(listOf(row("k1")))
        val enqueuer = FakeEnqueuer()
        val obs = observer(
            AppSettings(autoTranscribe = true, whisperEndpoint = "  "),
            dao, enqueuer, FakePathResolver("/local/v.mp4"),
        )
        obs.scanPending()
        assertTrue(enqueuer.calls.isEmpty())
        assertTrue(dao.enqueuedKeys.isEmpty())
    }

    @Test fun `scanPending marks CANCELLED and does not enqueue when path unresolvable`() = runBlocking {
        val dao = FakePendingTranscriptionDao(listOf(row("k1")))
        val enqueuer = FakeEnqueuer()
        val obs = observer(enabledSettings, dao, enqueuer, FakePathResolver(null))
        obs.scanPending()
        assertTrue(enqueuer.calls.isEmpty())
        assertTrue(dao.enqueuedKeys.isEmpty())
        assertEquals(listOf("k1" to "unresolvable_path"), dao.notTranscribable)
    }

    @Test fun `scanPending marks CANCELLED and does not enqueue for non-audio files`() = runBlocking {
        val dao = FakePendingTranscriptionDao(listOf(row("k1", filePath = "/stored/sub.srt")))
        val enqueuer = FakeEnqueuer()
        // Resolver returns a real path but with a subtitle extension (not audio-like).
        val obs = observer(enabledSettings, dao, enqueuer, FakePathResolver("/local/sub.srt"))
        obs.scanPending()
        assertTrue(enqueuer.calls.isEmpty())
        assertTrue(dao.enqueuedKeys.isEmpty())
        assertEquals(listOf("k1" to "not_audio_video"), dao.notTranscribable)
    }

    @Test fun `enqueue failure leaves row PENDING (no markEnqueued) for retry`() = runBlocking {
        val dao = FakePendingTranscriptionDao(listOf(row("k1")))
        val explodingEnqueuer = object : TranscriptionJobEnqueuer {
            override fun enqueueUnique(audioPath: String, sourceLang: String, requireWifi: Boolean, dedupKey: String) {
                throw IllegalStateException("WorkManager not initialized")
            }
        }
        val obs = observer(enabledSettings, dao, explodingEnqueuer, FakePathResolver("/local/v.mp4"))
        obs.scanPending()
        // enqueue threw → runCatching caught it → row stays PENDING (no markEnqueued).
        assertTrue(dao.enqueuedKeys.isEmpty())
        assertTrue(dao.notTranscribable.isEmpty())
    }
}
