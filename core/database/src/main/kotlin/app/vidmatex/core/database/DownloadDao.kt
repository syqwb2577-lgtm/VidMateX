package app.vidmatex.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("SELECT * FROM downloads ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observeById(id: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: String): DownloadEntity?

    /** Dedup: active/queued jobs with the same canonical source URL and quality label. */
    @Query(
        "SELECT * FROM downloads WHERE sourceUrl = :canonicalUrl " +
            "AND qualityLabel = :qualityLabel AND state IN ('QUEUED','ACTIVE')"
    )
    suspend fun getByCanonicalUrlAndQuality(
        canonicalUrl: String,
        qualityLabel: String,
    ): List<DownloadEntity>

    /**
     * Kind-aware dedup: active/queued jobs with the same canonical URL, quality label, and
     * primary stream kind. Legacy rows (NULL `trackKind`) are treated as `"video"` so old
     * records never collide with audio-only jobs; `:trackKind` is one of
     * `video` | `audio` | `subtitle`.
     */
    @Query(
        "SELECT * FROM downloads WHERE sourceUrl = :canonicalUrl " +
            "AND qualityLabel = :qualityLabel " +
            "AND IFNULL(trackKind, 'video') = :trackKind " +
            "AND state IN ('QUEUED','ACTIVE')"
    )
    suspend fun getActiveByUrlQualityAndKind(
        canonicalUrl: String,
        qualityLabel: String,
        trackKind: String,
    ): List<DownloadEntity>

    @Query("UPDATE downloads SET state = :state WHERE id = :id")
    suspend fun updateState(id: String, state: String)

    /** Persist the Range-resume verdict (206 / Accept-Ranges: bytes seen) for this job. */
    @Query("UPDATE downloads SET resumable = :resumable WHERE id = :id")
    suspend fun updateResumable(id: String, resumable: Boolean)

    @Query(
        "UPDATE downloads SET progressPercent = :progressPercent, speedBps = :speedBps, " +
            "etaSec = :etaSec, totalBytes = :totalBytes, downloadedBytes = :downloadedBytes WHERE id = :id"
    )
    suspend fun updateProgress(
        id: String,
        progressPercent: Int,
        speedBps: Long,
        etaSec: Long?,
        totalBytes: Long?,
        downloadedBytes: Long,
    )

    @Query("UPDATE downloads SET state = 'FAILED', errorMessage = :errorMessage, speedBps = 0, etaSec = NULL WHERE id = :id")
    suspend fun markFailed(id: String, errorMessage: String)

    @Query(
        "UPDATE downloads SET state = 'COMPLETED', progressPercent = 100, speedBps = 0, " +
            "etaSec = NULL, filePath = :filePath, subtitlePath = :subtitlePath, errorMessage = NULL WHERE id = :id"
    )
    suspend fun markCompleted(id: String, filePath: String?, subtitlePath: String?)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM downloads WHERE state = 'COMPLETED'")
    suspend fun clearCompleted()

    /** Remove finished history rows (files on disk are left untouched). */
    @Query("DELETE FROM downloads WHERE state IN ('COMPLETED','FAILED','CANCELLED')")
    suspend fun clearHistory()

    /**
     * Orphan marking (migration 3→4): set when a file-existence scan finds the media file
     * missing on disk (deleted outside the app). Advisory only; rows are never auto-deleted.
     */
    @Query("UPDATE downloads SET orphaned = :orphaned WHERE id = :id")
    suspend fun setOrphaned(id: String, orphaned: Boolean)

    /** Observe rows flagged as orphaned (file missing on disk); suspend variant for scans. */
    @Query("SELECT * FROM downloads WHERE orphaned = 1 ORDER BY createdAtMs DESC")
    fun observeOrphans(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE orphaned = 1")
    suspend fun getOrphans(): List<DownloadEntity>

    /**
     * Interrupted-job state cleanup (call on app start / WorkManager init). Jobs left in
     * ACTIVE state by a crashed process are reset to QUEUED (retryable) with speed/eta
     * cleared; jobs left in QUEUED are left untouched (WorkManager will reschedule).
     * Never touches COMPLETED/FAILED/CANCELLED/PAUSED rows. Returns the number of rows reset.
     */
    @Query("UPDATE downloads SET state = 'QUEUED', speedBps = 0, etaSec = NULL WHERE state = 'ACTIVE'")
    suspend fun resetInterruptedActiveJobs(): Int

    /**
     * Observe active/queued jobs (in-flight work) so the UI and worker coordinator can
     * reconcile state after process death without a full-table scan.
     */
    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED','ACTIVE') ORDER BY createdAtMs ASC")
    fun observeInFlight(): Flow<List<DownloadEntity>>
}
