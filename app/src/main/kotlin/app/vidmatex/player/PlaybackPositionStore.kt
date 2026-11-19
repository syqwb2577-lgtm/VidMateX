package app.vidmatex.player

/**
 * Pure playback-resume store contract (§3). No Android, Room, or Media3 imports so the
 * retention, eviction, and throttle rules run under plain JUnit.
 *
 * Keyed by a stable media id (the controller uses the media URI/Path string; Room rows
 * in `:core:database` use the same key). Persistence delegation:
 *  - [InMemoryPositionStore] is the always-available local fallback used when no Room
 *    DAO is wired into the player (see [RoomPlaybackPositionStore] for the adapter).
 *  - The Room table `playback_progress` (`core/database/PlaybackProgressDao`) is the
 *    durable backend; wiring depends on Hilt providing the DAO from the downloads
 *    module — see the cross-module note in `app/AUDIT_CHECKLIST.md`.
 */
data class PositionEntry(
    val mediaId: String,
    val positionMs: Long,
    val durationMs: Long = 0L,
    val completed: Boolean = false,
    val updatedAtMs: Long = 0L,
    /** Last user-selected speed, restored with the position. */
    val speed: Float = 1.0f,
    /** Last subtitle delay (ms) chosen for this item. */
    val subtitleDelayMs: Long = 0L,
)

interface PositionStore {
    suspend fun load(mediaId: String): PositionEntry?
    suspend fun save(entry: PositionEntry)
    /** Mark an item finished: position snapped to duration, completed flag set. */
    suspend fun markCompleted(mediaId: String, durationMs: Long, nowMs: Long)
    /** Apply retention rules; returns the number of entries removed. */
    suspend fun prune(nowMs: Long): Int
}

/**
 * Throttle/completion policy shared by the controller and tests. Mirrors
 * `core/database/PlaybackProgressPolicy` so the two backends agree on when a write
 * happens (5 s minimum advance; save immediately on completion).
 */
object PositionSavePolicy {
    const val MIN_SAVE_DELTA_MS = 5_000L
    const val COMPLETION_FRACTION = 0.95

    fun fraction(positionMs: Long, durationMs: Long): Double =
        if (durationMs <= 0L || positionMs <= 0L) 0.0
        else (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)

    fun isComplete(positionMs: Long, durationMs: Long): Boolean =
        fraction(positionMs, durationMs) >= COMPLETION_FRACTION

    /** True when enough progress advanced or playback reached the completion threshold. */
    fun shouldSave(currentMs: Long, lastSavedMs: Long, durationMs: Long): Boolean =
        currentMs - lastSavedMs >= MIN_SAVE_DELTA_MS || isComplete(currentMs, durationMs)
}

/**
 * Thread-safe in-memory fallback store. Retention rules:
 *  - completed entries older than [completedRetentionMs] are pruned;
 *  - when the map exceeds [maxEntries], oldest completed rows are evicted first;
 *    in-progress (incomplete) rows are retained until no completed rows remain;
 *  - completed rows keep their position at duration and are never advanced backwards.
 */
class InMemoryPositionStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val completedRetentionMs: Long = DEFAULT_COMPLETED_RETENTION_MS,
) : PositionStore {
    private val lock = Any()
    private val entries = LinkedHashMap<String, PositionEntry>()

    companion object {
        const val DEFAULT_MAX_ENTRIES = 100
        const val DEFAULT_COMPLETED_RETENTION_MS = 7L * 24 * 60 * 60 * 1_000L
    }

    override suspend fun load(mediaId: String): PositionEntry? = synchronized(lock) {
        entries[mediaId]
    }

    override suspend fun save(entry: PositionEntry) = synchronized(lock) {
        val existing = entries[entry.mediaId]
        val merged = when {
            existing?.completed == true -> existing // finished rows never regress
            entry.completed -> entry
            else -> entry
        }
        entries[entry.mediaId] = merged
        // Touch recency: move to the end (LRU order).
        entries.remove(entry.mediaId)
        entries[entry.mediaId] = merged
        enforceCapacityLocked()
    }

    override suspend fun markCompleted(mediaId: String, durationMs: Long, nowMs: Long) =
        synchronized(lock) {
            val current = entries[mediaId]
            entries[mediaId] = PositionEntry(
                mediaId = mediaId,
                positionMs = durationMs.coerceAtLeast(0L),
                durationMs = durationMs,
                completed = true,
                updatedAtMs = nowMs,
                speed = current?.speed ?: 1.0f,
                subtitleDelayMs = current?.subtitleDelayMs ?: 0L,
            )
        }

    override suspend fun prune(nowMs: Long): Int = synchronized(lock) {
        val before = entries.size
        entries.entries.removeIf { (_, e) ->
            e.completed && nowMs - e.updatedAtMs > completedRetentionMs
        }
        before - entries.size
    }

    /** Test-only inspection of the current entry count. */
    fun size(): Int = synchronized(lock) { entries.size }

    private fun enforceCapacityLocked() {
        while (entries.size > maxEntries) {
            // In-progress (incomplete) rows are never evicted while any completed row
            // remains: a user's current playback position must survive capacity pressure.
            // Evict oldest completed rows first, then oldest rows overall.
            val victim = entries.entries.firstOrNull { it.value.completed }?.key
                ?: entries.keys.firstOrNull()
            if (victim == null) break
            entries.remove(victim)
        }
    }
}
