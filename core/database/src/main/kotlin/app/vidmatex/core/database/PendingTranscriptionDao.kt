package app.vidmatex.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Durable pending-transcription queue. See [PendingTranscriptionEntity] for the
 * durability/idempotency contract. All mutations are keyed by the unique `dedupKey`
 * so concurrent observer scans and worker completions never double-enqueue or
 * double-mark.
 */
@Dao
interface PendingTranscriptionDao {
    /**
     * Raw insert with IGNORE conflict strategy. Returns the SQLite row id, or -1L when
     * the insert was ignored (a row with this `dedupKey` already exists — the UNIQUE
     * index violation). Prefer [insertPendingIfAbsent] which computes the dedup key and
     * returns a Boolean.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: PendingTranscriptionEntity): Long

    /**
     * Idempotent insert of a PENDING row. Computes `dedupKey` from [filePath] via
     * [PendingTranscriptionKeys.keyFor] (must match `TranscriptionDedup.keyFor`).
     *
     * @return true when a new PENDING row was inserted; false when a row with this
     *   dedupKey already exists (any status — including SUCCEEDED, in which case the
     *   file was already transcribed and nothing more is done), or when filePath is blank.
     * Single INSERT OR IGNORE — atomic in SQLite, no explicit transaction required.
     */
    suspend fun insertPendingIfAbsent(
        downloadId: String,
        filePath: String,
        subtitlePath: String?,
        title: String,
        sourceUrl: String,
    ): Boolean {
        val dedupKey = PendingTranscriptionKeys.keyFor(filePath)
        if (dedupKey.isBlank()) return false
        val now = System.currentTimeMillis()
        val rowId = insertIgnore(
            PendingTranscriptionEntity(
                id = java.util.UUID.randomUUID().toString(),
                downloadId = downloadId,
                filePath = filePath,
                subtitlePath = subtitlePath,
                title = title,
                sourceUrl = sourceUrl,
                dedupKey = dedupKey,
                status = PendingTranscriptionStatus.PENDING,
                attempts = 0,
                createdAtMs = now,
                updatedAtMs = now,
                errorMessage = null,
            )
        )
        return rowId != -1L
    }

    /** Rows awaiting enqueue (status=PENDING), oldest first. Called on app start. */
    @Query("SELECT * FROM pending_transcriptions WHERE status = 'PENDING' ORDER BY createdAtMs ASC")
    suspend fun findPending(): List<PendingTranscriptionEntity>

    /** Single row lookup by dedup key (tests / observability). */
    @Query("SELECT * FROM pending_transcriptions WHERE dedupKey = :dedupKey")
    suspend fun findByDedupKey(dedupKey: String): PendingTranscriptionEntity?

    /**
     * Observer enqueued a WorkManager job for this row. Only transitions PENDING→ENQUEUED
     * (guarded by `status = 'PENDING'`) so a concurrent scan cannot double-increment
     * attempts or re-mark an already-terminal row.
     */
    @Query(
        "UPDATE pending_transcriptions SET status = 'ENQUEUED', " +
            "attempts = attempts + 1, updatedAtMs = :nowMs " +
            "WHERE dedupKey = :dedupKey AND status = 'PENDING'"
    )
    suspend fun markEnqueued(dedupKey: String, nowMs: Long): Int

    /** TranscriptionWorker saved subtitles — terminal success. */
    @Query(
        "UPDATE pending_transcriptions SET status = 'SUCCEEDED', " +
            "updatedAtMs = :nowMs, errorMessage = NULL " +
            "WHERE dedupKey = :dedupKey"
    )
    suspend fun markSucceeded(dedupKey: String, nowMs: Long): Int

    /** TranscriptionWorker retries exhausted / permanent failure — terminal failure. */
    @Query(
        "UPDATE pending_transcriptions SET status = 'FAILED', " +
            "updatedAtMs = :nowMs, errorMessage = :errorMessage " +
            "WHERE dedupKey = :dedupKey"
    )
    suspend fun markFailed(dedupKey: String, errorMessage: String?, nowMs: Long): Int

    /**
     * File is not audio/video or path cannot be resolved — terminal, never retried.
     * Distinct from FAILED because it is not a transcription error.
     */
    @Query(
        "UPDATE pending_transcriptions SET status = 'CANCELLED', " +
            "updatedAtMs = :nowMs, errorMessage = :errorMessage " +
            "WHERE dedupKey = :dedupKey AND status = 'PENDING'"
    )
    suspend fun markNotTranscribable(dedupKey: String, errorMessage: String, nowMs: Long): Int

    /**
     * Worker was cancelled/stopped before completion — reset ENQUEUED→PENDING so the
     * next app-start scan re-enqueues. Guarded by `status = 'ENQUEUED'` so a terminal
     * row is never revived.
     */
    @Query(
        "UPDATE pending_transcriptions SET status = 'PENDING', " +
            "updatedAtMs = :nowMs " +
            "WHERE dedupKey = :dedupKey AND status = 'ENQUEUED'"
    )
    suspend fun resetToPending(dedupKey: String, nowMs: Long): Int
}
