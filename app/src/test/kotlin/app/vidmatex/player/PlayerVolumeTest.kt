package app.vidmatex.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-pure tests for [PlayerVolume] clamp / percent / mute / toggle rules. */
class PlayerVolumeTest {

    @Test
    fun clamp_withinBand_unchanged() {
        assertEquals(0.5f, PlayerVolume.clamp(0.5f), 0.0001f)
    }

    @Test
    fun clamp_belowMin_snapsToZero() {
        assertEquals(0f, PlayerVolume.clamp(-0.5f), 0.0001f)
        assertEquals(0f, PlayerVolume.clamp(-100f), 0.0001f)
    }

    @Test
    fun clamp_aboveMax_snapsToOne() {
        assertEquals(1f, PlayerVolume.clamp(1.5f), 0.0001f)
        assertEquals(1f, PlayerVolume.clamp(100f), 0.0001f)
    }

    @Test
    fun isMuted_zeroOrNearZero_true() {
        assertTrue(PlayerVolume.isMuted(0f))
        assertTrue(PlayerVolume.isMuted(0.0005f))
    }

    @Test
    fun isMuted_audible_false() {
        assertFalse(PlayerVolume.isMuted(0.1f))
        assertFalse(PlayerVolume.isMuted(1f))
    }

    @Test
    fun toPercent_roundsAndClamps() {
        assertEquals(0, PlayerVolume.toPercent(0f))
        assertEquals(50, PlayerVolume.toPercent(0.5f))
        assertEquals(100, PlayerVolume.toPercent(1f))
        assertEquals(100, PlayerVolume.toPercent(2f)) // out-of-band clamps
    }

    @Test
    fun fromPercent_inverseOfToPercent() {
        for (p in 0..100 step 10) {
            assertEquals(p, PlayerVolume.toPercent(PlayerVolume.fromPercent(p)))
        }
    }

    @Test
    fun toggleMute_fromAudible_mutes() {
        assertEquals(0f, PlayerVolume.toggleMute(0.7f), 0.0001f)
    }

    @Test
    fun toggleMute_fromMuted_restoresLastNonZero() {
        assertEquals(0.7f, PlayerVolume.toggleMute(0f, lastNonZero = 0.7f), 0.0001f)
    }

    @Test
    fun toggleMute_fromMutedNoLast_fallsBackToDefault() {
        assertEquals(PlayerVolume.DEFAULT, PlayerVolume.toggleMute(0f), 0.0001f)
    }
}
