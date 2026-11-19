package app.vidmatex.feature.history

import app.vidmatex.core.model.DownloadState
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * JVM-pure unit tests for the History display helpers. These have no Android imports,
 * so they run under `testDebugUnitTest` on the JVM. Not executed in this static source
 * environment (no Gradle/SDK); pending CI.
 */
class HistoryFormatTest {

    // --- bytes ---------------------------------------------------------------

    @Test
    fun `formatBytes null and non-positive show em dash`() {
        assertEquals("—", HistoryFormat.formatBytes(null))
        assertEquals("—", HistoryFormat.formatBytes(0))
        assertEquals("—", HistoryFormat.formatBytes(-12))
    }

    @Test
    fun `formatBytes picks B KB MB GB tiers`() {
        assertEquals("500 B", HistoryFormat.formatBytes(500))
        assertEquals("2 KB", HistoryFormat.formatBytes(1_536))
        assertEquals("5.0 MB", HistoryFormat.formatBytes(5_000_000))
        assertEquals("2.0 GB", HistoryFormat.formatBytes(2_000_000_000))
    }

    // --- speed ---------------------------------------------------------------

    @Test
    fun `formatSpeed zero or unknown shows dash`() {
        assertEquals("—", HistoryFormat.formatSpeed(0))
        assertEquals("—", HistoryFormat.formatSpeed(-1))
    }

    @Test
    fun `formatSpeed tiers B per second KB MB`() {
        assertEquals("800 B/s", HistoryFormat.formatSpeed(800))
        assertEquals("2 KB/s", HistoryFormat.formatSpeed(1_500))
        assertEquals("3.0 MB/s", HistoryFormat.formatSpeed(3_000_000))
    }

    // --- ETA -----------------------------------------------------------------

    @Test
    fun `formatEta null or non-positive shows dash`() {
        assertEquals("—", HistoryFormat.formatEta(null))
        assertEquals("—", HistoryFormat.formatEta(0))
        assertEquals("—", HistoryFormat.formatEta(-5))
    }

    @Test
    fun `formatEta renders minutes and hours`() {
        assertEquals("1:05", HistoryFormat.formatEta(65))
        assertEquals("1:01:01", HistoryFormat.formatEta(3_661))
    }

    // --- percent -------------------------------------------------------------

    @Test
    fun `percent prefers reported progress`() {
        assertEquals(42, HistoryFormat.percent(42, 250, 1_000))
    }

    @Test
    fun `percent derives from bytes when reported value absent`() {
        assertEquals(25, HistoryFormat.percent(0, 250, 1_000))
    }

    @Test
    fun `percent clamps to zero to hundred`() {
        assertEquals(0, HistoryFormat.percent(-9, 0, 1_000))
        assertEquals(100, HistoryFormat.percent(140, 2_000, 1_000))
        assertEquals(0, HistoryFormat.percent(0, 500, null))
    }

    // --- state badge mapping -------------------------------------------------

    @Test
    fun `badge key covers every download state`() {
        assertEquals("queued", HistoryBadge.key(DownloadState.QUEUED))
        assertEquals("active", HistoryBadge.key(DownloadState.ACTIVE))
        assertEquals("paused", HistoryBadge.key(DownloadState.PAUSED))
        assertEquals("completed", HistoryBadge.key(DownloadState.COMPLETED))
        assertEquals("failed", HistoryBadge.key(DownloadState.FAILED))
        assertEquals("cancelled", HistoryBadge.key(DownloadState.CANCELLED))
    }

    @Test
    fun `badge fallback labels are present for all states`() {
        DownloadState.values().forEach { state ->
            assertFalse("empty label for $state", HistoryBadge.fallbackLabel(state).isBlank())
        }
    }

    // --- size boundary edges (added coverage) -------------------------------
    @Test
    fun `formatBytes tier boundaries`() {
        assertEquals("999 B", HistoryFormat.formatBytes(999))
        assertEquals("1 KB", HistoryFormat.formatBytes(1_000))
        assertEquals("1000 KB", HistoryFormat.formatBytes(999_999))
        assertEquals("1.0 MB", HistoryFormat.formatBytes(1_000_000))
        assertEquals("1000.0 MB", HistoryFormat.formatBytes(999_999_999))
        assertEquals("1.0 GB", HistoryFormat.formatBytes(1_000_000_000))
    }

    // --- duration clock edges (added coverage) --------------------------------
    @Test
    fun `formatClock null zero and negative show dash`() {
        assertEquals("—", HistoryFormat.formatClock(null))
        assertEquals("—", HistoryFormat.formatClock(0))
        assertEquals("—", HistoryFormat.formatClock(-7))
    }

    @Test
    fun `formatClock minutes and hours`() {
        assertEquals("0:59", HistoryFormat.formatClock(59))
        assertEquals("1:00", HistoryFormat.formatClock(60))
        assertEquals("59:59", HistoryFormat.formatClock(3_599))
        assertEquals("1:00:00", HistoryFormat.formatClock(3_600))
        assertEquals("1:01:01", HistoryFormat.formatClock(3_661))
    }

    // --- date bucket edges (added coverage) ----------------------------------
    private fun millisAt(dayOffset: Int, hour: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        return c.timeInMillis
    }

    @Test
    fun `dateBucket sameCalendarDayIsToday_evenAcrossMorningEvening`() {
        assertEquals(
            HistoryFormat.DateBucket.TODAY,
            HistoryFormat.dateBucket(millisAt(0, 9), millisAt(0, 18)),
        )
    }

    @Test
    fun `dateBucket previousCalendarDayIsYesterday`() {
        assertEquals(
            HistoryFormat.DateBucket.YESTERDAY,
            HistoryFormat.dateBucket(millisAt(-1, 20), millisAt(0, 8)),
        )
    }

    @Test
    fun `dateBucket olderDaysAndUnknownValuesAreOlder`() {
        assertEquals(
            HistoryFormat.DateBucket.OLDER,
            HistoryFormat.dateBucket(millisAt(-3, 10), millisAt(0, 10)),
        )
        assertEquals(HistoryFormat.DateBucket.OLDER, HistoryFormat.dateBucket(0L, millisAt(0, 10)))
        assertEquals(HistoryFormat.DateBucket.OLDER, HistoryFormat.dateBucket(-1L, millisAt(0, 10)))
    }

    @Test
    fun `eta boundary just underAnHourAndExactlyHour`() {
        assertEquals("59:59", HistoryFormat.formatEta(3_599))
        assertEquals("1:00:00", HistoryFormat.formatEta(3_600))
    }
}
