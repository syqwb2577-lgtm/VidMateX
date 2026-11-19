package app.vidmatex.player

/**
 * Pure subtitle-delay (offset) math for the preview player (§3). No Android/Media3
 * imports: the controller keeps the selected delay in [Media3PlayerController.state]
 * and applies it to text-track timing; this helper owns every numeric conversion so
 * the rules are JVM-testable.
 *
 * Supported textual forms:
 *  - SRT cue timestamps: `HH:MM:SS,mmm` (comma milliseconds);
 *  - VTT/WebVTT cue timestamps: `HH:MM:SS.mmm` (dot milliseconds);
 *  - Signed user-facing delay labels produced by [formatSigned]: `+M:SS.s` / `-M:SS.s`,
 *    e.g. `+0:05.0`, `-1:30.5`;
 *  - Bare milliseconds or seconds (decimal) when no timestamp punctuation exists.
 *
 * Delays are clamped to ±[MAX_DELAY_MS] (one hour) both directions; negative values move
 * captions earlier, positive values later.
 */
object SubtitleDelay {
    const val MIN_DELAY_MS = -3_600_000L
    const val MAX_DELAY_MS = 3_600_000L

    /** UI step for the +/- buttons (half-second increments). */
    const val STEP_MS = 500L

    /** Clamp any raw delay into the supported signed range. */
    fun clamp(ms: Long): Long = ms.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)

    /**
     * Shift a cue's [startMs]/[endMs] by [delayMs] (already clamped). Negative-shifted
     * cue boundaries are snapped to zero so no cue ever starts before 00:00:00,000.
     */
    fun shiftCue(startMs: Long, endMs: Long, delayMs: Long): Pair<Long, Long> {
        val delta = clamp(delayMs)
        val shiftedStart = (startMs + delta).coerceAtLeast(0L)
        val shiftedEnd = (endMs + delta).coerceAtLeast(shiftedStart)
        return shiftedStart to shiftedEnd
    }

    /**
     * Absolute SRT/VTT cue timestamp (non-negative): `HH:MM:SS,mmm` when [comma] is
     * true (SRT), otherwise `HH:MM:SS.mmm` (VTT). Negative input snaps to 0.
     */
    fun formatCueTimestamp(ms: Long, comma: Boolean): String {
        val total = ms.coerceAtLeast(0L)
        val hours = total / 3_600_000L
        val minutes = (total % 3_600_000L) / 60_000L
        val seconds = (total % 60_000L) / 1_000L
        val millis = total % 1_000L
        val sep = if (comma) "," else "."
        return "%02d:%02d:%02d%s%03d".format(hours, minutes, seconds, sep, millis)
    }

    /** Compact signed label for the delay control, e.g. `+0:05.0`, `-1:30.5`, `0:00.0`. */
    fun formatSigned(ms: Long): String {
        val clamped = clamp(ms)
        val sign = when {
            clamped > 0 -> "+"
            clamped < 0 -> "-"
            else -> ""
        }
        val abs = kotlin.math.abs(clamped)
        val minutes = abs / 60_000L
        val seconds = (abs % 60_000L) / 1_000L
        val tenths = (abs % 1_000L) / 100L
        return "%s%d:%02d.%d".format(sign, minutes, seconds, tenths)
    }

    /**
     * Parse a delay/timestamp string into milliseconds. Accepts signed SRT/VTT
     * timestamps (`-00:01:30.500`, `+00:00:05,000`), compact signed labels, and bare
     * numbers (interpreted as milliseconds). Returns null for blank/malformed input;
     * never throws.
     */
    fun parseToMs(text: String?): Long? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val negative = raw.startsWith("-")
        val body = raw.trimStart('+', '-')
        val colonCount = body.count { it == ':' }
        val value = when {
            colonCount == 2 -> parseHms(body)
            colonCount == 1 -> parseMs(body)
            else -> body.toLongOrNull()
        } ?: return null
        val signedValue = if (negative) -value else value
        return clamp(signedValue)
    }

    private fun parseHms(body: String): Long? {
        val normalized = body.replace(',', '.')
        val parts = normalized.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val secParts = parts[2].split('.')
        val s = secParts[0].toLongOrNull() ?: return null
        val frac = when (secParts.size) {
            1 -> 0L
            2 -> (secParts[1].padEnd(3, '0').take(3)).toLongOrNull() ?: return null
            else -> return null
        }
        return h * 3_600_000L + m * 60_000L + s * 1_000L + frac
    }

    private fun parseMs(body: String): Long? {
        val parts = body.replace(',', '.').split(':')
        if (parts.size != 2) return null
        val m = parts[0].toLongOrNull() ?: return null
        val secParts = parts[1].split('.')
        val s = secParts[0].toLongOrNull() ?: return null
        val frac = when (secParts.size) {
            1 -> 0L
            2 -> (secParts[1].padEnd(3, '0').take(3)).toLongOrNull() ?: return null
            else -> return null
        }
        return m * 60_000L + s * 1_000L + frac
    }
}
