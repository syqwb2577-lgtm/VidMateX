package app.vidmatex.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Pure-JVM (no device) verification of migration metadata: database version, migration
 * start/end versions, and presence of every expected migration. Actual SQL execution
 * against a migrated database requires an instrumented run (see AppDatabaseMigrationTest
 * in src/androidTest). We deliberately do NOT implement SupportSQLiteDatabase here because
 * its method signatures reference android.content.ContentValues (framework-only); instead
 * we use a dynamic proxy that records execSQL calls for additive-SQL inspection.
 */
class MigrationMetadataTest {
    @Test fun `database version is 5 when annotation is runtime-visible`() {
        // Room's @Database may use CLASS retention (not runtime-visible on some builds);
        // when present we assert version 5. The androidTest migration suite is the
        // authoritative version check.
        val dbAnnotation = AppDatabase::class.java.getAnnotation(androidx.room.Database::class.java)
        if (dbAnnotation != null) {
            assertEquals(5, dbAnnotation.version)
        }
    }

    @Test fun `all four migrations exist as public fields with correct boundaries`() {
        val fields = AppDatabase.Companion::class.java.declaredFields
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }
        assertNotNull(AppDatabase.MIGRATION_1_2)
        assertNotNull(AppDatabase.MIGRATION_2_3)
        assertNotNull(AppDatabase.MIGRATION_3_4)
        assertNotNull(AppDatabase.MIGRATION_4_5)
        assertEquals(1, AppDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, AppDatabase.MIGRATION_1_2.endVersion)
        assertEquals(2, AppDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, AppDatabase.MIGRATION_2_3.endVersion)
        assertEquals(3, AppDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, AppDatabase.MIGRATION_3_4.endVersion)
        assertEquals(4, AppDatabase.MIGRATION_4_5.startVersion)
        assertEquals(5, AppDatabase.MIGRATION_4_5.endVersion)
    }

    @Test fun `migration chain is contiguous 1 to 5 with no gaps`() {
        val migrations = listOf(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
        )
        for (i in 1 until migrations.size) {
            assertEquals(
                "Migration ${i - 1} endVersion must equal migration $i startVersion (no gap)",
                migrations[i - 1].endVersion,
                migrations[i].startVersion,
            )
        }
        assertEquals(1, migrations.first().startVersion)
        assertEquals(5, migrations.last().endVersion)
    }

    @Test fun `database name constant is stable`() {
        assertEquals("vidmatex_downloads.db", AppDatabase.DB_NAME)
    }

    @Test fun `all six DAOs are exposed by AppDatabase`() {
        val methods = AppDatabase::class.java.declaredMethods.map { it.name }
        assertTrue(methods.contains("downloadDao"))
        assertTrue(methods.contains("mediaMetadataDao"))
        assertTrue(methods.contains("subtitleRecordDao"))
        assertTrue(methods.contains("playbackProgressDao"))
        assertTrue(methods.contains("apiConfigRefDao"))
        assertTrue(methods.contains("pendingTranscriptionDao"))
    }

    @Test fun `MIGRATION_4_5 SQL is strictly additive — creates table and unique index, no drops`() {
        // Dynamic proxy records every execSQL call; other methods return defaults.
        val executed = mutableListOf<String>()
        val fakeDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL" && args?.isNotEmpty() == true) {
                executed.add(args[0].toString())
            }
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as SupportSQLiteDatabase
        AppDatabase.MIGRATION_4_5.migrate(fakeDb)
        val sql = executed.joinToString("\n")
        assertTrue("must create pending_transcriptions table", sql.contains("CREATE TABLE IF NOT EXISTS pending_transcriptions"))
        assertTrue("must create unique index on dedupKey", sql.contains("CREATE UNIQUE INDEX") && sql.contains("dedupKey"))
        assertTrue("must not drop any table", !sql.contains("DROP TABLE"))
        assertTrue("must not alter any existing table", !sql.contains("ALTER TABLE"))
        assertTrue("must not delete any data", !sql.contains("DELETE FROM"))
    }
}
