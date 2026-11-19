package app.vidmatex.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM-pure tests for [SubtitleDelay]: ms↔timestamp conversion (SRT comma / VTT dot),
 * signed display labels, clamps, cue shifting, and malformed-input rejection.
 */
class SubtitleDelayTest {

    @Test
    fun clamp_enforcesOneHourBand() {
        assertEquals(3_600_000L, SubtitleDelay.clamp(9_000_000L))
        assertEquals(-3_600_000L, SubtitleDelay.clamp(-9_000_000L))
        assertEquals(500L, SubtitleDelay.clamp(500L))
        assertEquals(0L, SubtitleDelay.clamp(0L))
    }

    @Test
    fun srtTimestamp_usesCommaMilliseconds() {
        assertEquals("00:00:05,000", SubtitleDelay.formatCueTimestamp(5_000L, comma = true))
        assertEquals("01:02:03,250", SubtitleDelay.formatCueTimestamp(3_723_250L, comma = true))
        assertEquals("00:00:00,000", SubtitleDelay.formatCueTimestamp(-42L, comma = true))
    }

    @Test
    fun vttTimestamp_usesDotMilliseconds() {
        assertEquals("00:00:05.000", SubtitleDelay.formatCueTimestamp(5_000L, comma = false))
        assertEquals("01:02:03.250", SubtitleDelay.formatCueTimestamp(3_723_250L, comma = false))
    }

    @Test
    fun signedLabel_hasSignAndTenths() {
        assertEquals("+0:05.0", SubtitleDelay.formatSigned(5_000L))
        assertEquals("-1:30.5", SubtitleDelay.formatSigned(-90_500L))
        assertEquals("0:00.0", SubtitleDelay.formatSigned(0L))
    }

    @Test
    fun parse_acceptsSrtCommaVttDot_andSignedForms() {
        assertEquals(5_000L, SubtitleDelay.parseToMs("00:00:05,000"))
        assertEquals(5_000L, SubtitleDelay.parseToMs("00:00:05.000"))
        assertEquals(-90_500L, SubtitleDelay.parseToMs("-00:01:30.500"))
        assertEquals(500L, SubtitleDelay.parseToMs("+500"))
        assertEquals(65_000L, SubtitleDelay.parseToMs("1:05.000"))
    }

    @Test
    fun parse_rejectsBlankMalformedAndJunk() {
        assertNull(SubtitleDelay.parseToMs(null))
        assertNull(SubtitleDelay.parseToMs(""))
        assertNull(SubtitleDelay.parseToMs("   "))
        assertNull(SubtitleDelay.parseToMs("not-a-time"))
        assertNull(SubtitleDelay.parseToMs("mm:ss"))
    }

    @Test
    fun parse_roundTripsThroughSignedLabelMagnitude() {
        val original = 12_500L
        val parsed = SubtitleDelay.parseToMs(SubtitleDelay.formatSigned(original))
        // Signed label keeps tenths; 12.5s = 12_500 ms exactly.
        assertEquals(original, parsed)
    }

    @Test
    fun shiftCue_snapsNegativeStartToZero_andKeepsEndAfterStart() {
        val (start, end) = SubtitleDelay.shiftCue(200L, 4_200L, -3_000L)
        assertEquals(0L, start)
        assertEquals(1_200L, end)
        val (s2, e2) = SubtitleDelay.shiftCue(1_000L, 4_000L, 2_500L)
        assertEquals(3_500L, s2)
        assertEquals(6_500L, e2)
    }

    @Test
    fun step_isHalfSecond() {
        assertEquals(500L, SubtitleDelay.STEP_MS)
    }
}
