package app.vidmatex.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [PlaybackSpeed]: ladder progression, clamping, snapping, labels.
 * No Android/Media3 imports.
 */
class PlaybackSpeedTest {

    @Test
    fun ladder_startsAtQuarterAndEndsAtDouble() {
        assertEquals(0.25f, PlaybackSpeed.LADDER.first(), 0.0001f)
        assertEquals(2.0f, PlaybackSpeed.LADDER.last(), 0.0001f)
        assertTrue(PlaybackSpeed.LADDER.isSorted())
    }

    @Test
    fun belowMinimum_clampsToMinimum() {
        assertEquals(0.25f, PlaybackSpeed.clamp(0.05f), 0.0001f)
        assertEquals(0.25f, PlaybackSpeed.nearestSupported(0.01f), 0.0001f)
    }

    @Test
    fun aboveMaximum_clampsToMaximum() {
        assertEquals(2.0f, PlaybackSpeed.clamp(5f), 0.0001f)
        assertEquals(2.0f, PlaybackSpeed.nearestSupported(9f), 0.0001f)
    }

    @Test
    fun unsnappedRate_snapsToNearestLadderStep() {
        assertEquals(1.0f, PlaybackSpeed.nearestSupported(1.0001f), 0.0001f)
        assertEquals(0.75f, PlaybackSpeed.nearestSupported(0.7f), 0.0001f)
        assertEquals(0.75f, PlaybackSpeed.nearestSupported(0.8f), 0.0001f)
        assertEquals(1.5f, PlaybackSpeed.nearestSupported(1.4f), 0.0001f)
        assertEquals(1.75f, PlaybackSpeed.nearestSupported(1.8f), 0.0001f)
    }

    @Test
    fun stepUp_and_stepDown_walkTheLadder() {
        assertEquals(0.5f, PlaybackSpeed.stepUp(0.25f), 0.0001f)
        assertEquals(2.0f, PlaybackSpeed.stepUp(2.0f), 0.0001f)
        assertEquals(1.75f, PlaybackSpeed.stepDown(2.0f), 0.0001f)
        assertEquals(0.25f, PlaybackSpeed.stepDown(0.25f), 0.0001f)
        // Walking up from normal reaches 1.25, then 1.5 …
        var r = 1.0f
        repeat(3) { r = PlaybackSpeed.stepUp(r) }
        assertEquals(1.75f, r, 0.0001f)
    }

    @Test
    fun supportedRecognition_exactLadderOnly() {
        assertTrue(PlaybackSpeed.isSupported(1.25f))
        assertTrue(PlaybackSpeed.isSupported(0.5f))
        assertFalse(PlaybackSpeed.isSupported(0.9f))
        assertFalse(PlaybackSpeed.isSupported(3f))
    }

    @Test
    fun labels_normalIsOneX_othersKeepTwoDecimalsTrimmed() {
        assertEquals("1x", PlaybackSpeed.label(1.0f))
        assertEquals("0.5x", PlaybackSpeed.label(0.5f))
        assertEquals("1.25x", PlaybackSpeed.label(1.25f))
        assertEquals("2x", PlaybackSpeed.label(2.0f))
        assertEquals("0.25x", PlaybackSpeed.label(0.25f))
    }

    // --- Tie-break regression (weighted-scalar bug) --------------------------
    // A previous implementation scored candidates as `|candidate - rate| * 2 - candidate`.
    // The tie-break term (-candidate) varied by 0.25 between adjacent ladder steps — the
    // same magnitude as the distance term at the midpoint — so the higher candidate won
    // even when it was NOT the nearest (e.g. 0.35 wrongly snapped to 0.5x instead of the
    // closer 0.25x). nearestSupported must resolve by distance first, tie to higher.

    @Test
    fun nearestBelowMidpoint_snapsToLowerStep_notHigher() {
        // 0.35 is closer to 0.25 (dist 0.10) than to 0.5 (dist 0.15).
        assertEquals(0.25f, PlaybackSpeed.nearestSupported(0.35f), 0.0001f)
        // 1.1 is closer to 1.0 (dist 0.10) than to 1.25 (dist 0.15).
        assertEquals(1.0f, PlaybackSpeed.nearestSupported(1.1f), 0.0001f)
        // 1.6 is closer to 1.5 (dist 0.10) than to 1.75 (dist 0.15).
        assertEquals(1.5f, PlaybackSpeed.nearestSupported(1.6f), 0.0001f)
    }

    @Test
    fun exactMidpoint_tieBreaksTowardHigherSpeed() {
        // Midpoint of 0.25 and 0.5 is 0.375 — equal distance, higher (0.5) wins.
        assertEquals(0.5f, PlaybackSpeed.nearestSupported(0.375f), 0.0001f)
        // Midpoint of 1.0 and 1.25 is 1.125 — higher (1.25) wins.
        assertEquals(1.25f, PlaybackSpeed.nearestSupported(1.125f), 0.0001f)
    }

    @Test
    fun nearestAboveMidpoint_snapsToHigherStep() {
        // 0.4 is closer to 0.5 (dist 0.10) than to 0.25 (dist 0.15).
        assertEquals(0.5f, PlaybackSpeed.nearestSupported(0.4f), 0.0001f)
        // 1.2 is closer to 1.25 (dist 0.05) than to 1.0 (dist 0.20).
        assertEquals(1.25f, PlaybackSpeed.nearestSupported(1.2f), 0.0001f)
    }

    private fun List<Float>.isSorted(): Boolean =
        zipWithNext().all { (a, b) -> a <= b }
}
