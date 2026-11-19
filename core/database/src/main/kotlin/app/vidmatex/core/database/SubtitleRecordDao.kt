package app.vidmatex.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** CRUD for [SubtitleRecordEntity] (additive table, migration 2→3). */
@Dao
interface SubtitleRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SubtitleRecordEntity)

    @Query("SELECT * FROM subtitle_records WHERE mediaId = :mediaId ORDER BY createdAtMs ASC")
    suspend fun listForMedia(mediaId: String): List<SubtitleRecordEntity>

    @Query("SELECT * FROM subtitle_records WHERE mediaId = :mediaId")
    fun observeForMedia(mediaId: String): Flow<List<SubtitleRecordEntity>>

    /**
     * Dedup lookup before upsert: the unique index (mediaId, trackId) guarantees at most
     * one row; this returns it so callers can merge rather than REPLACE blindly.
     */
    @Query("SELECT * FROM subtitle_records WHERE mediaId = :mediaId AND trackId = :trackId LIMIT 1")
    suspend fun getByMediaAndTrackId(mediaId: String, trackId: String): SubtitleRecordEntity?

    /** Mark a track fetched; records the local sidecar path and translated flag. */
    @Query("UPDATE subtitle_records SET filePath = :filePath, translated = :translated WHERE id = :id")
    suspend fun markFetched(id: String, filePath: String?, translated: Boolean)

    @Query("DELETE FROM subtitle_records WHERE mediaId = :mediaId")
    suspend fun deleteForMedia(mediaId: String)

    @Query("DELETE FROM subtitle_records WHERE createdAtMs < :beforeMs")
    suspend fun pruneOlderThan(beforeMs: Long)
}
