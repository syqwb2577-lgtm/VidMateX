package app.vidmatex.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.vidmatex.core.model.DownloadState
import app.vidmatex.core.model.SourceType

/**
 * Persisted download record. Mirrors [app.vidmatex.core.model.DownloadItem] and adds
 * [specJson] (serialized [app.vidmatex.core.model.DownloadSpec]) so a failed/paused job
 * can be retried without re-resolving the source. Enums are stored as TEXT via [Converters].
 */
@Entity(
    tableName = "downloads",
    indices = [Index("state"), Index("createdAtMs")],
)
data class DownloadEntity(
    @PrimaryKey val id: String,
    val sourceUrl: String,
    val sourceType: SourceType,
    val title: String,
    val thumbnailUrl: String? = null,
    val qualityLabel: String? = null,
    val state: DownloadState,
    val progressPercent: Int = 0,
    val speedBps: Long = 0,
    val etaSec: Long? = null,
    val totalBytes: Long? = null,
    val downloadedBytes: Long = 0,
    /** Final content:// URI (MediaStore / SAF document) or absolute file path on API < 29. */
    val filePath: String? = null,
    /** Separate subtitle file URI/path; null when embedded or not requested. */
    val subtitlePath: String? = null,
    val errorMessage: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    /** Serialized DownloadSpec (JSON TEXT) used to retry/resume the job. */
    val specJson: String,
    /**
     * True when the stream answered 206 Partial Content / sent `Accept-Ranges: bytes` for
     * this job. Additive default `false`; Room column `resumable INTEGER NOT NULL DEFAULT 0`
     * (migration 1→2). Absence/false means: re-probe, never append onto an unverified partial.
     */
    val resumable: Boolean = false,
    /**
     * Primary stream kind: `"video"` | `"audio"` | `"subtitle"`; powers kind-aware dedup.
     * Additive nullable TEXT column `trackKind`; legacy rows read back as `null` and the DAO
     * treats `IFNULL(trackKind, 'video')` as the video default.
     */
    val trackKind: String? = null,
    /**
     * True when a file-existence scan found the downloaded media file missing on disk
     * (deleted outside the app). Additive default `false`; Room column
     * `orphaned INTEGER NOT NULL DEFAULT 0` (migration 3→4). Advisory only — the UI shows
     * "file missing"; rows are never auto-deleted. Reset to false when a re-download completes.
     */
    @ColumnInfo(defaultValue = "0") val orphaned: Boolean = false,
)
