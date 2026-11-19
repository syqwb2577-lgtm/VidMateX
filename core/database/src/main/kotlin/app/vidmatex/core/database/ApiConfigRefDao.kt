package app.vidmatex.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for [ApiConfigRefEntity] (additive table, migration 2→3). Rows hold non-secret
 * endpoint references only; secret material lives in the encrypted secrets store and
 * is referenced through [ApiConfigRefEntity.keyAlias].
 */
@Dao
interface ApiConfigRefDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ApiConfigRefEntity)

    @Query("SELECT * FROM api_config_refs WHERE alias = :alias")
    suspend fun getByAlias(alias: String): ApiConfigRefEntity?

    @Query("SELECT * FROM api_config_refs ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<ApiConfigRefEntity>>

    /** Record the result of a Settings "Test connection" probe (status text only, no tokens). */
    @Query(
        "UPDATE api_config_refs SET lastTestedAtMs = :ts, lastStatus = :status, updatedAtMs = :ts " +
            "WHERE alias = :alias"
    )
    suspend fun markTested(alias: String, ts: Long, status: String)

    @Query("DELETE FROM api_config_refs WHERE alias = :alias")
    suspend fun deleteByAlias(alias: String)
}
