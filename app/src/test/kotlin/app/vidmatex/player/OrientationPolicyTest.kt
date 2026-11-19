package app.vidmatex.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-pure tests for [OrientationPolicy] recommendation inputs. */
class OrientationPolicyTest {

    @Test
    fun wideVideo_recommendsLandscape() {
        val rec = OrientationPolicy.recommend(1920, 1080, 60_000L)
        assertTrue(rec.preferLandscape)
        assertEquals(OrientationPolicy.REASON_WIDE_VIDEO, rec.reason)
    }

    @Test
    fun portraitVideo_recommendsPortrait() {
        val rec = OrientationPolicy.recommend(1080, 1920, 60_000L)
        assertFalse(rec.preferLandscape)
        assertEquals(OrientationPolicy.REASON_PORTRAIC_VIDEO, rec.reason)
    }

    @Test
    fun unknownShapeLongForm_recommendsLandscapeByDuration() {
        val rec = OrientationPolicy.recommend(0, 0, 15 * 60 * 1_000L)
        assertTrue(rec.preferLandscape)
        assertEquals(OrientationPolicy.REASON_LONG_FORM, rec.reason)
    }

    @Test
    fun unknownShapeShortClip_staysPortraitNeutral() {
        val rec = OrientationPolicy.recommend(-1, -1, 30_000L)
        assertFalse(rec.preferLandscape)
        assertEquals(OrientationPolicy.REASON_UNKNOWN, rec.reason)
    }

    @Test
    fun squareVideo_treatsAsWide_landscape() {
        // width >= height (including square) leans landscape per the policy.
        val rec = OrientationPolicy.recommend(720, 720, 60_000L)
        assertTrue(rec.preferLandscape)
        assertEquals(OrientationPolicy.REASON_WIDE_VIDEO, rec.reason)
    }

    @Test
    fun exactlyLongFormThreshold_recommendsLandscape() {
        val rec = OrientationPolicy.recommend(0, 0, OrientationPolicy.LONG_FORM_THRESHOLD_MS)
        assertTrue(rec.preferLandscape)
        assertEquals(OrientationPolicy.REASON_LONG_FORM, rec.reason)
    }

    @Test
    fun knownShapeOverridesDuration_portraitLongStaysPortrait() {
        // A tall video is portrait even when it is long-form: shape wins over duration.
        val rec = OrientationPolicy.recommend(1080, 1920, OrientationPolicy.LONG_FORM_THRESHOLD_MS * 2)
        assertFalse(rec.preferLandscape)
        assertEquals(OrientationPolicy.REASON_PORTRAIC_VIDEO, rec.reason)
    }

    @Test
    fun zeroDurationUnknownShape_unknown() {
        val rec = OrientationPolicy.recommend(0, 0, 0L)
        assertFalse(rec.preferLandscape)
        assertEquals(OrientationPolicy.REASON_UNKNOWN, rec.reason)
    }
}
