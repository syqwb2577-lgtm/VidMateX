package app.vidmatex.core.database

import app.vidmatex.core.model.DownloadState
import app.vidmatex.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit checks for the additive Room entity columns requested in
 * `_spec/CHANGES_REQUESTED.md` E §2.1 (`resumable`, `trackKind`).
 *
 * Scope boundary (environment note): this source set must NOT call Android frameworks.
 * Not covered here and requiring an instrumented test on device/CI (Android SDK + Gradle):
 *  - Room schema generation for version 2 and `AppDatabase.MIGRATION_1_2` executing
 *    `ALTER TABLE downloads ADD COLUMN resumable INTEGER NOT NULL DEFAULT 0` and
 *    `ALTER TABLE downloads ADD COLUMN trackKind TEXT` against a migrated v1 database;
 *  - `DownloadDao.getActiveByUrlQualityAndKind` (SQLite `IFNULL(trackKind,'video')` matching
 *    QUEUED/ACTIVE rows) and `DownloadDao.updateResumable(id, resumable)` persistence.
 */
class DownloadEntityDefaultsTest {

    private fun buildEntity(
        resumable: Boolean = false,
        trackKind: String? = null,
    ) = DownloadEntity(
        id = "item-1",
        sourceUrl = "https://example.com/audio.m4a",
        sourceType = SourceType.DIRECT_URL,
        title = "Test download",
        state = DownloadState.QUEUED,
        specJson = "{}",
        resumable = resumable,
        trackKind = trackKind,
    )

    @Test fun `additive columns default to non-resumable and null kind`() {
        val entity = buildEntity()
        assertFalse(entity.resumable)
        assertNull(entity.trackKind)
    }

    @Test fun `additive columns carry explicit values through copy`() {
        val entity = buildEntity().copy(resumable = true, trackKind = "audio")
        assertEquals(true, entity.resumable)
        assertEquals("audio", entity.trackKind)
    }

    @Test fun `legacy construction without new arguments still yields additive defaults`() {
        // v1-style call: only pre-E §2.1 fields supplied; defaults mirror the SQL
        // column defaults (resumable DEFAULT 0, trackKind NULL).
        val legacy = DownloadEntity(
            id = "item-legacy",
            sourceUrl = "https://example.com/old.mp4",
            sourceType = SourceType.YOUTUBE,
            title = "Legacy row",
            state = DownloadState.COMPLETED,
            specJson = "{}",
        )
        assertFalse(legacy.resumable)
        assertNull(legacy.trackKind)
    }

    // --- v3→v4 additive: orphaned flag (column `orphaned INTEGER NOT NULL DEFAULT 0`) ---
    @Test fun `orphaned flag defaults to false`() {
        assertFalse(buildEntity().orphaned)
    }

    @Test fun `orphaned flag carries explicit value through copy`() {
        val marked = buildEntity().copy(orphaned = true)
        assertTrue(marked.orphaned)
        // A re-download resets the advisory flag back to false.
        assertFalse(marked.copy(orphaned = false).orphaned)
    }

    @Test fun `construction omitting all additive columns yields stable defaults`() {
        val minimal = DownloadEntity(
            id = "min",
            sourceUrl = "https://example.com/v.mp4",
            sourceType = SourceType.DIRECT_URL,
            title = "Min",
            state = DownloadState.QUEUED,
            specJson = "{}",
        )
        assertFalse(minimal.resumable)
        assertNull(minimal.trackKind)
        assertFalse(minimal.orphaned)
        assertEquals(0, minimal.progressPercent)
        assertEquals(0L, minimal.downloadedBytes)
    }
}
