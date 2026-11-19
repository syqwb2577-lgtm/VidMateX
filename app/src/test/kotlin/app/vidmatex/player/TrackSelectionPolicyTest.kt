package app.vidmatex.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [TrackSelectionPolicy]: single-selection semantics, OFF index,
 * unsupported-track rejection, and default-track preference by language.
 */
class TrackSelectionPolicyTest {

    private fun options() = listOf(
        TrackOption("a0", 0, SeamTrackType.AUDIO, "English", "en", supported = true),
        TrackOption("a1", 1, SeamTrackType.AUDIO, "Arabic", "ar", supported = true),
        TrackOption("a2", 2, SeamTrackType.AUDIO, "Legacy AC3", "en", supported = false),
    )

    @Test
    fun select_marksExactlyOneSupportedTrack() {
        val updated = TrackSelectionPolicy.select(options(), 1)
        assertEquals(1, updated.count { it.selected })
        assertTrue(updated.first { it.index == 1 }.selected)
        assertEquals(1, TrackSelectionPolicy.selectedIndex(updated))
    }

    @Test
    fun offIndex_clearsEverySelection() {
        val selected = TrackSelectionPolicy.select(options(), 0)
        val off = TrackSelectionPolicy.select(selected, TrackSelectionPolicy.OFF_INDEX)
        assertTrue(off.none { it.selected })
        assertEquals(TrackSelectionPolicy.OFF_INDEX, TrackSelectionPolicy.selectedIndex(off))
    }

    @Test
    fun unsupportedIndex_leavesListUnchanged() {
        val before = options()
        val after = TrackSelectionPolicy.select(before, 2)
        assertEquals(before, after)
    }

    @Test
    fun outOfRangeIndex_leavesListUnchanged() {
        val before = options()
        val after = TrackSelectionPolicy.select(before, 99)
        assertEquals(before, after)
    }

    @Test
    fun defaultOption_prefersMatchingLanguage_thenFirstSupported() {
        val preferred = TrackSelectionPolicy.defaultOption(options(), "ar")
        assertEquals("Arabic", preferred?.label)
        val fallback = TrackSelectionPolicy.defaultOption(options(), "fr")
        assertEquals("English", fallback?.label)
        val none = TrackSelectionPolicy.defaultOption(emptyList())
        assertNull(none)
    }

    @Test
    fun displayLabel_fallsBackToLanguageThenIndex() {
        val withLabel = TrackOption("k", 0, SeamTrackType.SUBTITLE, "English", "en")
        assertEquals("English", TrackSelectionPolicy.displayLabel(withLabel))
        val noLabel = TrackOption("k", 2, SeamTrackType.SUBTITLE, "", "de")
        assertEquals("de", TrackSelectionPolicy.displayLabel(noLabel))
        val bare = TrackOption("k", 3, SeamTrackType.SUBTITLE, "", null)
        assertEquals("Track 4", TrackSelectionPolicy.displayLabel(bare))
    }

    // --- Multi-group key resolution (regression) ------------------------------
    // Media3 can expose multiple track groups of the same type (e.g. an embedded audio
    // group and a sidecar group, or two HLS variant audio groups). Each group numbers its
    // own tracks from 0, so `index` is NOT unique across groups — the stable `key`
    // (which includes the group ordinal) is. selectByKey must mark exactly the chosen
    // track selected, not every track that shares its within-group index.

    private fun multiGroupAudio() = listOf(
        // group 0: tracks index 0 (English) and 1 (Spanish)
        TrackOption("audio-g0-t0", 0, SeamTrackType.AUDIO, "English", "en", supported = true),
        TrackOption("audio-g0-t1", 1, SeamTrackType.AUDIO, "Spanish", "es", supported = true),
        // group 1: tracks index 0 (Commentary) and 1 (Director's cut) — index collides with group 0
        TrackOption("audio-g1-t0", 0, SeamTrackType.AUDIO, "Commentary", "en", supported = true),
        TrackOption("audio-g1-t1", 1, SeamTrackType.AUDIO, "Director's cut", "en", supported = true),
    )

    @Test
    fun selectByKey_multiGroup_marksExactlyOneTrackEvenWhenIndicesCollide() {
        val updated = TrackSelectionPolicy.selectByKey(multiGroupAudio(), "audio-g1-t0")
        assertEquals(1, updated.count { it.selected })
        assertTrue(updated.first { it.key == "audio-g1-t0" }.selected)
        // The group-0 track that shares index 0 must NOT be selected.
        assertFalse(updated.first { it.key == "audio-g0-t0" }.selected)
    }

    @Test
    fun selectByKey_multiGroup_selectingGroupZeroDoesNotTouchGroupOne() {
        val updated = TrackSelectionPolicy.selectByKey(multiGroupAudio(), "audio-g0-t1")
        assertTrue(updated.first { it.key == "audio-g0-t1" }.selected)
        assertFalse(updated.first { it.key == "audio-g1-t1" }.selected)
        assertEquals(1, updated.count { it.selected })
    }

    @Test
    fun selectByKey_unknownOrUnsupportedKey_leavesListUnchanged() {
        val before = multiGroupAudio()
        assertEquals(before, TrackSelectionPolicy.selectByKey(before, "does-not-exist"))
        val unsupported = before.map { if (it.key == "audio-g1-t0") it.copy(supported = false) else it }
        assertEquals(unsupported, TrackSelectionPolicy.selectByKey(unsupported, "audio-g1-t0"))
    }

    @Test
    fun selectedIndex_multiGroup_returnsIndexOfTheSingleSelectedTrack() {
        val updated = TrackSelectionPolicy.selectByKey(multiGroupAudio(), "audio-g1-t1")
        assertEquals(1, TrackSelectionPolicy.selectedIndex(updated))
        val off = TrackSelectionPolicy.select(updated, TrackSelectionPolicy.OFF_INDEX)
        assertEquals(TrackSelectionPolicy.OFF_INDEX, TrackSelectionPolicy.selectedIndex(off))
    }
}
