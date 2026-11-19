package app.vidmatex.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration verification stubs. Uses real [AppDatabase.MIGRATION_1_2], [MIGRATION_2_3],
 * and [MIGRATION_3_4] classes via [MigrationTestHelper].
 *
 * IMPLEMENTED BUT NOT VERIFIED: these tests require an Android device/emulator and cannot
 * run in this environment. They are written to be compile-ready and correct per Room's
 * migration-test contract. Run with:
 *   ./gradlew :core:database:connectedDebugAndroidTest
 *
 * Each test creates a database at an old schema version, inserts representative data,
 * runs the migration, and validates that (a) the migration succeeds and (b) user data
 * survives (no destructive recreation).
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val testDb = "migration-test.db"

    @Test
    fun migrate1To2_preservesDownloadRowsAndAddsColumns() {
        // Create schema at version 1 with a download row.
        helper.createDatabase(testDb, 1).apply {
            execSQL(
                """
                INSERT INTO downloads (id, sourceUrl, sourceType, title, state, progressPercent,
                    speedBps, downloadedBytes, createdAtMs, specJson)
                VALUES ('d1', 'https://example.com/v.mp4', 'DIRECT_URL', 'Video', 'QUEUED',
                    0, 0, 0, 1000, '{}')
                """.trimIndent()
            )
            close()
        }
        // Run 1→2.
        helper.runMigrationsAndValidate(testDb, 2, true, AppDatabase.MIGRATION_1_2).apply {
            // Legacy row survives; new columns have defaults.
            query("SELECT id, resumable, trackKind FROM downloads WHERE id = 'd1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(c.getColumnIndexOrThrow("resumable")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("trackKind")))
            }
            close()
        }
    }

    @Test
    fun migrate2To3_addsFourTablesWithoutTouchingDownloads() {
        helper.createDatabase(testDb, 2).apply {
            execSQL(
                """
                INSERT INTO downloads (id, sourceUrl, sourceType, title, state, progressPercent,
                    speedBps, downloadedBytes, createdAtMs, specJson, resumable, trackKind)
                VALUES ('d2', 'https://example.com/a.m4a', 'DIRECT_URL', 'Audio', 'COMPLETED',
                    100, 0, 5000, 2000, '{}', 1, 'audio')
                """.trimIndent()
            )
            close()
        }
        helper.runMigrationsAndValidate(testDb, 3, true, AppDatabase.MIGRATION_2_3).apply {
            // Existing download row untouched.
            query("SELECT state, trackKind, resumable FROM downloads WHERE id = 'd2'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("COMPLETED", c.getString(0))
                assertEquals("audio", c.getString(1))
                assertEquals(1, c.getInt(2))
            }
            // New tables exist and start empty.
            for (table in listOf("media_metadata", "subtitle_records", "playback_progress", "api_config_refs")) {
                query("SELECT COUNT(*) FROM $table").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(0, c.getInt(0))
                }
            }
            close()
        }
    }

    @Test
    fun migrate3To4_addsOrphanedColumnAndUniqueSubtitleIndex() {
        helper.createDatabase(testDb, 3).apply {
            execSQL(
                """
                INSERT INTO downloads (id, sourceUrl, sourceType, title, state, progressPercent,
                    speedBps, downloadedBytes, createdAtMs, specJson, resumable)
                VALUES ('d3', 'https://example.com/v.mp4', 'DIRECT_URL', 'Video', 'COMPLETED',
                    100, 0, 9000, 3000, '{}', 1)
                """.trimIndent()
            )
            // Two duplicate subtitle records for the same media+track (pre-unique-index).
            execSQL(
                """
                INSERT INTO subtitle_records (id, mediaId, trackId, languageCode, languageName, kind)
                VALUES ('s1', 'm1', 't1', 'en', 'English', 'OFFICIAL')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO subtitle_records (id, mediaId, trackId, languageCode, languageName, kind)
                VALUES ('s2', 'm1', 't1', 'en', 'English', 'OFFICIAL')
                """.trimIndent()
            )
            close()
        }
        helper.runMigrationsAndValidate(testDb, 4, true, AppDatabase.MIGRATION_3_4).apply {
            // Download row survives; orphaned defaults to 0.
            query("SELECT orphaned FROM downloads WHERE id = 'd3'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
            // Duplicate subtitle rows deduplicated to exactly one (newest kept).
            query("SELECT COUNT(*) FROM subtitle_records WHERE mediaId = 'm1' AND trackId = 't1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            // Unique index exists.
            query(
                """
                SELECT name FROM sqlite_master WHERE type = 'index'
                AND name = 'index_subtitle_records_mediaId_trackId'
                """.trimIndent()
            ).use { c -> assertTrue(c.moveToFirst()) }
            close()
        }
    }

    @Test
    fun migrate4To5_addsPendingTranscriptionsTableWithUniqueIndex() {
        helper.createDatabase(testDb, 4).apply {
            execSQL(
                """
                INSERT INTO downloads (id, sourceUrl, sourceType, title, state, progressPercent,
                    speedBps, downloadedBytes, createdAtMs, specJson, resumable, orphaned)
                VALUES ('d4', 'https://example.com/v.mp4', 'DIRECT_URL', 'Video', 'COMPLETED',
                    100, 0, 9000, 4000, '{}', 1, 0)
                """.trimIndent()
            )
            close()
        }
        helper.runMigrationsAndValidate(testDb, 5, true, AppDatabase.MIGRATION_4_5).apply {
            // Existing download row survives (strictly additive).
            query("SELECT state, orphaned FROM downloads WHERE id = 'd4'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("COMPLETED", c.getString(0))
                assertEquals(0, c.getInt(1))
            }
            // New table exists and starts empty.
            query("SELECT COUNT(*) FROM pending_transcriptions").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
            // Unique index on dedupKey exists.
            query(
                """
                SELECT name FROM sqlite_master WHERE type = 'index'
                AND name = 'index_pending_transcriptions_dedupKey'
                """.trimIndent()
            ).use { c -> assertTrue(c.moveToFirst()) }
            // New columns have defaults matching the entity.
            query(
                """
                INSERT INTO pending_transcriptions (id, downloadId, filePath, title, sourceUrl, dedupKey)
                VALUES ('p1', 'd4', '/sdcard/v.mp4', 'Video', 'https://e/v', 'k1')
                """.trimIndent()
            )
            query("SELECT status, attempts, createdAtMs FROM pending_transcriptions WHERE id = 'p1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("PENDING", c.getString(0))
                assertEquals(0, c.getInt(1))
                assertEquals(0, c.getLong(2))
            }
            close()
        }
    }

    @Test
    fun migrateAllFrom1To4_succeedsAndPreservesData() {
        helper.createDatabase(testDb, 1).apply {
            execSQL(
                """
                INSERT INTO downloads (id, sourceUrl, sourceType, title, state, progressPercent,
                    speedBps, downloadedBytes, createdAtMs, specJson)
                VALUES ('d-all', 'https://example.com/v.mp4', 'DIRECT_URL', 'All', 'QUEUED',
                    0, 0, 0, 4000, '{}')
                """.trimIndent()
            )
            close()
        }
        // Run the full chain 1→2→3→4.
        helper.runMigrationsAndValidate(
            testDb, 4, true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
        ).apply {
            query("SELECT id, orphaned, resumable FROM downloads WHERE id = 'd-all'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(c.getColumnIndexOrThrow("orphaned")))
                assertEquals(0, c.getInt(c.getColumnIndexOrThrow("resumable")))
            }
            close()
        }
    }
}
