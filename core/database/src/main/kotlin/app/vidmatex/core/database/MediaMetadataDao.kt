package app.vidmatex.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for [MediaMetadataEntity]. Query execution is Room/SQLite (instrumented on
 * device/emulator); the entity defaults and any pure mapping logic are covered by JVM
 * unit tests.
 */
@Dao
interface MediaMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaMetadataEntity)

    @Query("SELECT * FROM media_metadata WHERE id = :id")
    suspend fun getById(id: String): MediaMetadataEntity?

    @Query("SELECT * FROM media_metadata WHERE id = :id")
    fun observeById(id: String): Flow<MediaMetadataEntity?>

    @Query("SELECT * FROM media_metadata ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<MediaMetadataEntity>>

    @Query("SELECT * FROM media_metadata WHERE sourceUrl = :sourceUrl ORDER BY updatedAtMs DESC")
    suspend fun getBySourceUrl(sourceUrl: String): List<MediaMetadataEntity>

    @Query("DELETE FROM media_metadata WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Housekeeping: drop rows not touched since [beforeMs] (caller chooses the TTL). */
    @Query("DELETE FROM media_metadata WHERE updatedAtMs < :beforeMs")
    suspend fun pruneOlderThan(beforeMs: Long)
}
