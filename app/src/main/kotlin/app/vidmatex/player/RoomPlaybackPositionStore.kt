package app.vidmatex.player

import app.vidmatex.core.database.PlaybackProgressDao
import app.vidmatex.core.database.PlaybackProgressEntity

/**
 * Room-backed [PositionStore] adapter (§3 resume hook) over `:core:database`'s
 * `playback_progress` table (`PlaybackProgressDao`).
 *
 * WIRING STATUS (cross-module dependency): `DownloadModule` in `:feature:downloads`
 * currently provides only `DownloadDao`; until it also exposes `PlaybackProgressDao`
 * via Hilt and registers `AppDatabase.MIGRATION_2_3`, the player constructs the
 * [InMemoryPositionStore] fallback. This adapter is source-ready and swaps in by
 * injecting the DAO:
 * `Media3PlayerController(context, positionStore = RoomPlaybackPositionStore(dao))`.
 *
 * Table columns cover position/duration/completed/updatedAt only; the extra session
 * fields ([PositionEntry.speed], [PositionEntry.subtitleDelayMs]) remain in the
 * in-memory fallback and restore per-session, not durably. Extending the entity is a
 * `:core:database` change (owned elsewhere) and is listed in `app/AUDIT_CHECKLIST.md` §5.
 */
class RoomPlaybackPositionStore(
    private val dao: PlaybackProgressDao,
) : PositionStore {

    override suspend fun load(mediaId: String): PositionEntry? =
        dao.getByMediaId(mediaId)?.toEntry()

    override suspend fun save(entry: PositionEntry) {
        dao.upsert(entry.toEntity())
    }

    override suspend fun markCompleted(mediaId: String, durationMs: Long, nowMs: Long) {
        // Persist the final position at duration first, then the DAO's completed flag.
        dao.upsert(
            PlaybackProgressEntity(
                mediaId = mediaId,
                positionMs = durationMs.coerceAtLeast(0L),
                durationMs = durationMs,
                completed = true,
                updatedAtMs = nowMs,
            ),
        )
        dao.markCompleted(mediaId, nowMs)
    }

    override suspend fun prune(nowMs: Long): Int {
        val cutoff = nowMs - InMemoryPositionStore.DEFAULT_COMPLETED_RETENTION_MS
        // Room's DELETE query returns row count in Java but the current DAO signature is
        // Unit; removal count therefore reports 0 here (policy enforcement still runs).
        dao.pruneCompletedOlderThan(cutoff)
        return 0
    }

    private fun PlaybackProgressEntity.toEntry(): PositionEntry = PositionEntry(
        mediaId = mediaId,
        positionMs = positionMs,
        durationMs = durationMs,
        completed = completed,
        updatedAtMs = updatedAtMs,
    )

    private fun PositionEntry.toEntity(): PlaybackProgressEntity = PlaybackProgressEntity(
        mediaId = mediaId,
        positionMs = positionMs,
        durationMs = durationMs,
        completed = completed,
        updatedAtMs = updatedAtMs,
    )
}
