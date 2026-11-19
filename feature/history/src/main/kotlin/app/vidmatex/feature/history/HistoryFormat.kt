package app.vidmatex.feature.history

import app.vidmatex.core.model.DownloadState
import java.util.Calendar
import java.util.Locale

/**
 * JVM-pure display formatters for the History screen. No Android imports so these can
 * be exercised by local unit tests. Terminology mirrors the Downloads module's
 * `FormatUtils` (bytes/speed/ETA); formatting lives here because the History module
 * cannot depend on `:feature:downloads`.
 */
object HistoryFormat {

    /** Human-readable byte size. Null/zero/negative → an em dash placeholder. */
    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return "—"
        return when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(Locale.US, bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(Locale.US, bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(Locale.US, bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    /** Download speed in bytes/sec → localized-unit string (numbers stay LTR). */
    fun formatSpeed(bps: Long): String = when {
        bps >= 1_000_000 -> "%.1f MB/s".format(Locale.US, bps / 1_000_000.0)
        bps >= 1_000 -> "%.0f KB/s".format(Locale.US, bps / 1_000.0)
        bps > 0 -> "$bps B/s"
        else -> "—"
    }

    /** Remaining-time label. Null/unknown or non-positive → an em dash placeholder. */
    fun formatEta(sec: Long?): String {
        if (sec == null || sec <= 0) return "—"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s)
        else "%d:%02d".format(Locale.US, m, s)
    }

    /**
     * Resolved progress percentage (0..100). Prefers the reported `progressPercent`;
     * when the total size is known but the reported percent is absent, derives it from
     * `downloadedBytes / totalBytes`.
     */
    fun percent(progressPercent: Int, downloadedBytes: Long, totalBytes: Long?): Int {
        val derived = if (totalBytes != null && totalBytes > 0) {
            ((downloadedBytes * 100.0 / totalBytes)).toInt()
        } else {
            0
        }
        val value = if (progressPercent > 0) progressPercent else derived
        return value.coerceIn(0, 100)
    }

    /** Clock-style duration label for a media/ETA value in seconds; null/≤0 → em dash. */
    fun formatClock(sec: Long?): String {
        if (sec == null || sec <= 0L) return "—"
        val h = sec / 3600
        val m = (sec % 3_600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s)
        else "%d:%02d".format(Locale.US, m, s)
    }

    /** Calendar-day bucket for history grouping (pure JVM; uses the default timezone). */
    enum class DateBucket { TODAY, YESTERDAY, OLDER }

    fun dateBucket(createdAtMs: Long, nowMs: Long): DateBucket {
        if (createdAtMs <= 0L) return DateBucket.OLDER
        if (dayKey(createdAtMs) == dayKey(nowMs)) return DateBucket.TODAY
        val yesterdayCal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val yesterdayKey = yesterdayCal.get(Calendar.YEAR) to yesterdayCal.get(Calendar.DAY_OF_YEAR)
        if (dayKey(createdAtMs) == yesterdayKey) return DateBucket.YESTERDAY
        return DateBucket.OLDER
    }

    private fun dayKey(ms: Long): Pair<Int, Int> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        return cal.get(Calendar.YEAR) to cal.get(Calendar.DAY_OF_YEAR)
    }
}

/**
 * Pure state-badge mapping. The machine [key] is stable for tests; the UI maps a state
 * to a localized string resource, and [fallbackLabel] provides a neutral English label
 * only as a defensive fallback (never used when a string resource exists).
 */
object HistoryBadge {

    fun key(state: DownloadState): String = when (state) {
        DownloadState.QUEUED -> "queued"
        DownloadState.ACTIVE -> "active"
        DownloadState.PAUSED -> "paused"
        DownloadState.COMPLETED -> "completed"
        DownloadState.FAILED -> "failed"
        DownloadState.CANCELLED -> "cancelled"
    }

    fun fallbackLabel(state: DownloadState): String = when (state) {
        DownloadState.QUEUED -> "Queued"
        DownloadState.ACTIVE -> "Downloading"
        DownloadState.PAUSED -> "Paused"
        DownloadState.COMPLETED -> "Completed"
        DownloadState.FAILED -> "Failed"
        DownloadState.CANCELLED -> "Cancelled"
    }
}
