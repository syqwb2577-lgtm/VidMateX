package app.vidmatex.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** CRUD for [PlaybackProgressEntity] (additive table, migration 2→3). */
@Dao
interface PlaybackProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackProgressEntity)

    @Query("SELECT * FROM playback_progress WHERE mediaId = :mediaId")
    suspend fun getByMediaId(mediaId: String): PlaybackProgressEntity?

    @Query("SELECT * FROM playback_progress WHERE mediaId = :mediaId")
    fun observeByMediaId(mediaId: String): Flow<PlaybackProgressEntity?>

    /** Mark playback finished: completed flag on, position snapped to duration, timestamp bumped. */
    @Query("UPDATE playback_progress SET completed = 1, positionMs = durationMs, updatedAtMs = :ts WHERE mediaId = :mediaId")
    suspend fun markCompleted(mediaId: String, ts: Long)

    /** Cleanup helper: remove finished rows older than [beforeMs] (caller-chosen retention). */
    @Query("DELETE FROM playback_progress WHERE completed = 1 AND updatedAtMs < :beforeMs")
    suspend fun pruneCompletedOlderThan(beforeMs: Long)

    @Query("DELETE FROM playback_progress WHERE mediaId = :mediaId")
    suspend fun deleteByMediaId(mediaId: String)
}
