package app.vidmatex.feature.downloads

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests for [FormatUtils] (notification/downloads-screen display
 * formatters). No Android framework calls; safe for `testDebugUnitTest`.
 *
 * Status: source-ready pending CI — this environment has no Android SDK/Gradle
 * (host JDK 11 while the project targets JVM 17), so these cases were NOT executed
 * here. They must run in CI under JDK 17 (`./gradlew testDebugUnitTest`).
 */
class FormatUtilsTest {

    @Test
    fun `formatSpeed picks B KB MB tiers`() {
        assertEquals("0 B/s", FormatUtils.formatSpeed(0L))
        assertEquals("999 B/s", FormatUtils.formatSpeed(999L))
        assertEquals("2 KB/s", FormatUtils.formatSpeed(1500L))
        assertEquals("1000 KB/s", FormatUtils.formatSpeed(1_000_000L - 1L))
        assertEquals("2.5 MB/s", FormatUtils.formatSpeed(2_500_000L))
        assertEquals("1.0 MB/s", FormatUtils.formatSpeed(1_000_000L))
    }

    @Test
    fun `formatEta renders zero minutes and hours`() {
        assertEquals("0:00", FormatUtils.formatEta(0L))
        assertEquals("0:00", FormatUtils.formatEta(-12L))
        assertEquals("0:45", FormatUtils.formatEta(45L))
        assertEquals("1:05", FormatUtils.formatEta(65L))
        assertEquals("1:01:01", FormatUtils.formatEta(3661L))
    }

    @Test
    fun `formatBytes null and non-positive collapse to dash`() {
        assertEquals("—", FormatUtils.formatBytes(null))
        assertEquals("—", FormatUtils.formatBytes(0L))
        assertEquals("—", FormatUtils.formatBytes(-7L))
    }

    @Test
    fun `formatBytes picks B KB MB GB tiers`() {
        assertEquals("500 B", FormatUtils.formatBytes(500L))
        assertEquals("2 KB", FormatUtils.formatBytes(1500L))
        assertEquals("2.5 MB", FormatUtils.formatBytes(2_500_000L))
        assertEquals("3.2 GB", FormatUtils.formatBytes(3_200_000_000L))
    }
}
