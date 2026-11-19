package app.vidmatex.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        DownloadEntity::class,
        MediaMetadataEntity::class,
        SubtitleRecordEntity::class,
        PlaybackProgressEntity::class,
        ApiConfigRefEntity::class,
        PendingTranscriptionEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun mediaMetadataDao(): MediaMetadataDao
    abstract fun subtitleRecordDao(): SubtitleRecordDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun apiConfigRefDao(): ApiConfigRefDao
    abstract fun pendingTranscriptionDao(): PendingTranscriptionDao

    companion object {
        const val DB_NAME = "vidmatex_downloads.db"

        /**
         * Migration 1 → 2 (additive columns, no data rewrite):
         *  - `resumable INTEGER NOT NULL DEFAULT 0` — Range-resume capability probe result.
         *  - `trackKind TEXT` (nullable) — "video" | "audio" | "subtitle"; legacy rows are
         *    NULL and DAO queries apply `IFNULL(trackKind, 'video')`.
         * Kept here so `DownloadModule` registers the real migration instead of destructive
         * fallback. `exportSchema = false` is unchanged (no schema JSON committed).
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE downloads ADD COLUMN resumable INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE downloads ADD COLUMN trackKind TEXT")
            }
        }

        /**
         * Migration 2 → 3 (STRICTLY ADDITIVE): four new lookup/history tables with indexes
         * and column defaults. No table is dropped, no existing column is altered/dropped,
         * and no existing row is deleted. New tables start empty; old `downloads` rows are
         * untouched.
         *
         *  - `media_metadata`      — resolved video/media metadata cache.
         *  - `subtitle_records`    — offered/fetched subtitle track records + sidecar paths.
         *  - `playback_progress`   — per-media resume position.
         *  - `api_config_refs`     — NON-SECRET endpoint references (alias/base URL/auth
         *    mode label/timestamps only; keys stay in the encrypted secrets store).
         *
         * Column defaults below mirror the entity `@ColumnInfo(defaultValue = …)` values so
         * Room's generated schema and the migrated SQLite schema match exactly.
         *
         * REGISTRATION (out of this module's ownership): `feature/downloads/DownloadModule.kt`
         * must add this migration to the builder, e.g.
         *   `.addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)`
         * otherwise Room falls back to destructive recreation on upgrade. DAO execution and
         * migration verification require an instrumented/device run (see TESTING_GUIDE §4).
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS media_metadata (
                        id TEXT PRIMARY KEY NOT NULL,
                        sourceUrl TEXT NOT NULL,
                        sourceType TEXT NOT NULL,
                        title TEXT NOT NULL,
                        author TEXT,
                        durationSec INTEGER,
                        thumbnailUrl TEXT,
                        description TEXT,
                        kind TEXT NOT NULL DEFAULT 'video',
                        createdAtMs INTEGER NOT NULL DEFAULT 0,
                        updatedAtMs INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_media_metadata_sourceUrl ON media_metadata(sourceUrl)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_media_metadata_createdAtMs ON media_metadata(createdAtMs)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subtitle_records (
                        id TEXT PRIMARY KEY NOT NULL,
                        mediaId TEXT NOT NULL,
                        trackId TEXT NOT NULL,
                        languageCode TEXT NOT NULL,
                        languageName TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        format TEXT NOT NULL DEFAULT 'srt',
                        filePath TEXT,
                        isTranslatable INTEGER NOT NULL DEFAULT 1,
                        translated INTEGER NOT NULL DEFAULT 0,
                        createdAtMs INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_subtitle_records_mediaId ON subtitle_records(mediaId)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playback_progress (
                        mediaId TEXT PRIMARY KEY NOT NULL,
                        positionMs INTEGER NOT NULL DEFAULT 0,
                        durationMs INTEGER NOT NULL DEFAULT 0,
                        completed INTEGER NOT NULL DEFAULT 0,
                        updatedAtMs INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_playback_progress_updatedAtMs ON playback_progress(updatedAtMs)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS api_config_refs (
                        alias TEXT PRIMARY KEY NOT NULL,
                        provider TEXT NOT NULL,
                        baseUrl TEXT NOT NULL DEFAULT '',
                        endpointPath TEXT NOT NULL DEFAULT '',
                        authMode TEXT NOT NULL DEFAULT 'none',
                        keyAlias TEXT NOT NULL DEFAULT '',
                        createdAtMs INTEGER NOT NULL DEFAULT 0,
                        updatedAtMs INTEGER NOT NULL DEFAULT 0,
                        lastTestedAtMs INTEGER NOT NULL DEFAULT 0,
                        lastStatus TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Migration 3 → 4 (STRICTLY ADDITIVE, user data preserved):
         *  1. `downloads.orphaned INTEGER NOT NULL DEFAULT 0` — marks rows whose media file on
         *     disk was deleted outside the app (file missing on scan). Additive ALTER TABLE;
         *     existing rows default to 0 (not orphaned). The repository sets the flag on a
         *     file-existence scan; the flag is advisory (UI shows "file missing") and never
         *     deletes a row automatically.
         *  2. Unique index `index_subtitle_records_mediaId_trackId` on
         *     `subtitle_records(mediaId, trackId)` — prevents duplicate subtitle records for
         *     the same media+track. Before creating the unique index, duplicate rows are
         *     deduplicated keeping the row with the largest `rowid` (newest insert). No data
         *     is lost beyond exact duplicates. `CREATE UNIQUE INDEX IF NOT EXISTS` is idempotent.
         *
         * REGISTRATION (out of this module's ownership): `feature/downloads/DownloadModule.kt`
         * must add this migration to the builder, e.g.
         *   `.addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)`
         * otherwise Room falls back to destructive recreation on upgrade. DAO execution and
         * migration verification require an instrumented/device run (see TESTING_GUIDE §4).
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Additive orphaned flag on downloads (default 0 = not orphaned).
                database.execSQL("ALTER TABLE downloads ADD COLUMN orphaned INTEGER NOT NULL DEFAULT 0")
                // 2. Deduplicate subtitle_records by (mediaId, trackId), keeping the newest
                //    insert (largest rowid). Exact duplicates only; distinct tracks survive.
                database.execSQL(
                    """
                    DELETE FROM subtitle_records
                    WHERE rowid NOT IN (
                        SELECT MAX(rowid) FROM subtitle_records GROUP BY mediaId, trackId
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_subtitle_records_mediaId_trackId " +
                        "ON subtitle_records(mediaId, trackId)"
                )
            }
        }

        /**
         * Migration 4 → 5 (STRICTLY ADDITIVE, no data loss): one new table
         * `pending_transcriptions` with a UNIQUE index on `dedupKey`. No existing table
         * is altered, dropped, or rewritten; no existing row is deleted. The new table
         * starts empty. Column defaults mirror the [PendingTranscriptionEntity]
         * `@ColumnInfo`/constructor defaults so Room's generated schema and the migrated
         * SQLite schema match exactly.
         *
         * Purpose: durable pending-transcription queue. The in-memory CompletedDownloadBus
         * (replay=0) loses completed downloads on process death; this table is written
         * atomically with download completion and scanned on app start so transcription
         * is enqueued exactly once (see [PendingTranscriptionEntity] KDoc).
         *
         * REGISTRATION (out of this module's ownership): `feature/downloads/DownloadModule.kt`
         * must add this migration to the builder, e.g.
         *   `.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)`
         * otherwise Room falls back to destructive recreation on upgrade.
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_transcriptions (
                        id TEXT PRIMARY KEY NOT NULL,
                        downloadId TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        subtitlePath TEXT,
                        title TEXT NOT NULL,
                        sourceUrl TEXT NOT NULL,
                        dedupKey TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        attempts INTEGER NOT NULL DEFAULT 0,
                        createdAtMs INTEGER NOT NULL DEFAULT 0,
                        updatedAtMs INTEGER NOT NULL DEFAULT 0,
                        errorMessage TEXT
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_pending_transcriptions_dedupKey " +
                        "ON pending_transcriptions(dedupKey)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_pending_transcriptions_status " +
                        "ON pending_transcriptions(status)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_pending_transcriptions_createdAtMs " +
                        "ON pending_transcriptions(createdAtMs)"
                )
            }
        }
    }
}
