package app.vidmatex.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [InMemoryPositionStore] retention/eviction and [PositionSavePolicy]
 * throttle rules. The Room adapter (`RoomPlaybackPositionStore`) requires a device and is
 * covered by instrumented verification only.
 */
class PlaybackPositionStoreTest {

    private val now = 1_000_000_000_000L

    @Test
    fun save_then_load_restoresPositionSpeedAndDelay() = runBlocking {
        val store = InMemoryPositionStore()
        store.save(
            PositionEntry(
                mediaId = "media-1",
                positionMs = 42_500L,
                durationMs = 120_000L,
                updatedAtMs = now,
                speed = 1.25f,
                subtitleDelayMs = -500L,
            ),
        )
        val restored = store.load("media-1")
        assertEquals(42_500L, restored?.positionMs)
        assertEquals(120_000L, restored?.durationMs)
        assertEquals(1.25f, restored?.speed)
        assertEquals(-500L, restored?.subtitleDelayMs)
        assertFalse(restored?.completed ?: true)
    }

    @Test
    fun unknownMediaId_returnsNull() = runBlocking {
        assertNull(InMemoryPositionStore().load("missing"))
    }

    @Test
    fun completedRow_neverRegressesOnLaterSave() = runBlocking {
        val store = InMemoryPositionStore()
        store.save(PositionEntry("m", 90_000L, 100_000L, completed = true, updatedAtMs = now))
        store.save(PositionEntry("m", 10_000L, 100_000L, completed = false, updatedAtMs = now + 1_000))
        val row = store.load("m")!!
        assertTrue(row.completed)
        assertEquals(90_000L, row.positionMs)
    }

    @Test
    fun markCompleted_snapsPositionToDuration() = runBlocking {
        val store = InMemoryPositionStore()
        store.save(PositionEntry("m", 30_000L, 100_000L, updatedAtMs = now))
        store.markCompleted("m", 100_000L, now + 5_000)
        val row = store.load("m")!!
        assertTrue(row.completed)
        assertEquals(100_000L, row.positionMs)
        assertEquals(now + 5_000, row.updatedAtMs)
    }

    @Test
    fun prune_removesOldCompletedRows_keepsRecentAndActive() = runBlocking {
        val store = InMemoryPositionStore(completedRetentionMs = 1_000L)
        store.save(PositionEntry("old-done", 9L, 10L, completed = true, updatedAtMs = now - 5_000))
        store.save(PositionEntry("new-done", 9L, 10L, completed = true, updatedAtMs = now))
        store.save(PositionEntry("active", 4L, 10L, completed = false, updatedAtMs = now - 5_000))
        val removed = store.prune(now)
        assertEquals(1, removed)
        assertNull(store.load("old-done"))
        assertNotNull(store.load("new-done"))
        assertNotNull(store.load("active"))
    }

    @Test
    fun capacityEviction_dropsOldestIncompleteBeforeCompleted() = runBlocking {
        val store = InMemoryPositionStore(maxEntries = 5, completedRetentionMs = 10_000L)
        repeat(5) { i ->
            store.save(PositionEntry("done-$i", 90L, 100L, completed = true, updatedAtMs = now + i))
        }
        // Two more active rows push capacity; completed rows stay until retention.
        store.save(PositionEntry("active-1", 10L, 100L, completed = false, updatedAtMs = now + 10))
        store.save(PositionEntry("active-2", 20L, 100L, completed = false, updatedAtMs = now + 11))
        assertTrue(store.size() <= 5)
        assertNotNull(store.load("active-1"))
        assertNotNull(store.load("active-2"))
    }

    @Test
    fun throttle_savesAfterFiveSecondsAdvance_orCompletion() {
        assertFalse(PositionSavePolicy.shouldSave(1_000L, 500L, 100_000L))
        assertTrue(PositionSavePolicy.shouldSave(6_000L, 500L, 100_000L))
        // 96% through a 100s video saves even under the time delta.
        assertTrue(PositionSavePolicy.shouldSave(96_500L, 96_000L, 100_000L))
        assertFalse(PositionSavePolicy.isComplete(50_000L, 100_000L))
        assertTrue(PositionSavePolicy.isComplete(96_000L, 100_000L))
        assertEquals(0.0, PositionSavePolicy.fraction(0L, 100_000L), 0.0001)
        assertEquals(0.5, PositionSavePolicy.fraction(50_000L, 100_000L), 0.0001)
        assertEquals(0.0, PositionSavePolicy.fraction(50_000L, 0L), 0.0001)
    }

    private fun assertNotNull(value: Any?) = assertTrue("expected non-null", value != null)
}
