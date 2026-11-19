package app.vidmatex.feature.downloads

import app.vidmatex.core.model.MediaKind
import app.vidmatex.core.model.MediaTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for [FormatSelectionPolicy] (requirement §4 quality/speed fallback model).
 * No Android imports. Source-ready pending CI.
 */
class FormatSelectionPolicyTest {

    private fun video(id: String, height: Int, bitrate: Int = 0) = MediaTrack(
        trackId = id,
        kind = MediaKind.VIDEO,
        container = "mp4",
        heightPx = height,
        bitrateBps = bitrate,
    )

    private fun audio(id: String, bitrate: Int, sampleRate: Int = 44_100) = MediaTrack(
        trackId = id,
        kind = MediaKind.AUDIO,
        container = "m4a",
        bitrateBps = bitrate,
        sampleRate = sampleRate,
    )

    @Test
    fun `bestVideo picks highest height then bitrate`() {
        val tracks = listOf(video("v480", 480), video("v1080", 1080), video("v720", 720))
        assertEquals("v1080", FormatSelectionPolicy.bestVideo(tracks)?.trackId)
        val sameHeight = listOf(video("a", 720, bitrate = 800_000), video("b", 720, bitrate = 2_500_000))
        assertEquals("b", FormatSelectionPolicy.bestVideo(sameHeight)?.trackId)
    }

    @Test
    fun `bestAudio picks highest bitrate then sample rate`() {
        val tracks = listOf(audio("a", 128_000), audio("b", 256_000), audio("c", 96_000))
        assertEquals("b", FormatSelectionPolicy.bestAudio(tracks)?.trackId)
    }

    @Test
    fun `bestEffort prefers video and degrades to audio then first item`() {
        assertEquals("v1", FormatSelectionPolicy.bestEffort(listOf(audio("a", 1), video("v1", 720)))!!.trackId)
        assertEquals("a", FormatSelectionPolicy.bestEffort(listOf(audio("a", 1)))!!.trackId)
        assertNull(FormatSelectionPolicy.bestEffort(emptyList()))
    }

    @Test
    fun `hasAnyTrack detects concrete selection`() {
        val v = video("v", 720)
        val a = audio("a", 128_000)
        assertEquals(true, FormatSelectionPolicy.hasAnyTrack(v, null))
        assertEquals(true, FormatSelectionPolicy.hasAnyTrack(null, a))
        assertEquals(false, FormatSelectionPolicy.hasAnyTrack(null, null))
    }
}
