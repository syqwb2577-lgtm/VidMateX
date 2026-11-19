package app.vidmatex.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.vidmatex.core.model.SourceType

/**
 * Cached video/media metadata resolved for a source URL (additive table, migration 2→3).
 * Mirrors [app.vidmatex.core.model.VideoMetadata] plus a coarse `kind` label used by
 * history/UI dedup. No secret material is stored here.
 */
@Entity(
    tableName = "media_metadata",
    indices = [Index("sourceUrl"), Index("createdAtMs")],
)
data class MediaMetadataEntity(
    @PrimaryKey val id: String,
    val sourceUrl: String,
    val sourceType: SourceType,
    val title: String,
    val author: String? = null,
    val durationSec: Long? = null,
    val thumbnailUrl: String? = null,
    val description: String? = null,
    /** "video" | "audio"; additive default mirrors SQL `DEFAULT 'video'`. */
    @ColumnInfo(defaultValue = "'video'") val kind: String = "video",
    @ColumnInfo(defaultValue = "0") val createdAtMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = 0L,
)
