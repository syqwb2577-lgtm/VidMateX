package app.vidmatex.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Playback position for a media item (additive table, migration 2→3). Keyed by the
 * same media id used elsewhere; used to resume the player. Non-secret by design.
 */
@Entity(
    tableName = "playback_progress",
    indices = [Index("updatedAtMs")],
)
data class PlaybackProgressEntity(
    @PrimaryKey val mediaId: String,
    @ColumnInfo(defaultValue = "0") val positionMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val completed: Boolean = false,
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = 0L,
)
