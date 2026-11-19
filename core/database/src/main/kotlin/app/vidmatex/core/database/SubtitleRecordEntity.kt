package app.vidmatex.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.vidmatex.core.model.SubtitleKind

/**
 * Persisted subtitle track record for a media item (additive table, migration 2→3).
 * One row per offered/fetched subtitle track; `filePath` is set once the sidecar file
 * exists locally. No secret material is stored here.
 */
@Entity(
    tableName = "subtitle_records",
    indices = [
        Index("mediaId"),
        // Prevents duplicate subtitle records for the same media + track.
        // Added in migration 3→4 (dedupe + CREATE UNIQUE INDEX IF NOT EXISTS).
        Index(value = ["mediaId", "trackId"], unique = true),
    ],
)
data class SubtitleRecordEntity(
    @PrimaryKey val id: String,
    /** Owning media row ([MediaMetadataEntity.id] / download item id). */
    val mediaId: String,
    val trackId: String,
    val languageCode: String,
    val languageName: String,
    val kind: SubtitleKind,
    @ColumnInfo(defaultValue = "'srt'") val format: String = "srt",
    /** Local sidecar file URI/path; null until fetched. */
    val filePath: String? = null,
    @ColumnInfo(defaultValue = "1") val isTranslatable: Boolean = true,
    /** True when the translated variant has been produced/fetched. */
    @ColumnInfo(defaultValue = "0") val translated: Boolean = false,
    @ColumnInfo(defaultValue = "0") val createdAtMs: Long = 0L,
)
