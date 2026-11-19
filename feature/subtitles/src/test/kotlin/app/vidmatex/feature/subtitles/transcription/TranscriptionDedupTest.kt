package app.vidmatex.feature.subtitles.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [TranscriptionDedup] (one active job per source slot) and the
 * in-process [ActiveTranscriptionSet]. No Android imports.
 */
class TranscriptionDedupTest {

    @Test
    fun `same normalized path yields same stable key`() {
        val a = TranscriptionDedup.keyFor("/storage/emulated/0/Recordings/lecture.m4a")
        val b = TranscriptionDedup.keyFor("/storage/emulated/0/Recordings/lecture.m4a")
        val c = TranscriptionDedup.keyFor("\\storage\\emulated\\0\\Recordings\\lecture.m4a")
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(64, a.length) // SHA-256 hex
    }

    @Test
    fun `different paths yield different keys and blank input yields blank key`() {
        val a = TranscriptionDedup.keyFor("/a/take1.mp3")
        val b = TranscriptionDedup.keyFor("/a/take2.mp3")
        assertTrue(a.isNotBlank())
        assertFalse(a == b)
        assertEquals("", TranscriptionDedup.keyFor(""))
        assertEquals("", TranscriptionDedup.keyFor("   "))
        assertTrue(TranscriptionDedup.sameSource("/a/x.mp3", "/a/x.mp3"))
        assertFalse(TranscriptionDedup.sameSource("/a/x.mp3", "/a/y.mp3"))
    }

    @Test
    fun `active registry rejects a second add and prunes on terminal state`() {
        val registry = ActiveTranscriptionSet()
        val key = TranscriptionDedup.keyFor("/job/audio.wav")
        assertTrue(registry.tryAdd(key))
        assertFalse(registry.tryAdd(key))
        assertEquals(1, registry.activeCount)
        registry.remove(key)
        assertTrue(registry.tryAdd(key))
        registry.clear()
        assertEquals(0, registry.activeCount)
        // Blank keys never enter the registry.
        assertTrue(ActiveTranscriptionSet().tryAdd(""))
    }
}
