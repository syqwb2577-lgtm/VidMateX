package app.vidmatex.feature.subtitles.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [SourceLanguages] dropdown <-> API mapping (blank = auto-detect; the
 * Whisper `language` form field is omitted in that case).
 *
 * Written but NOT executed in this environment (no Android SDK / Gradle; host JDK 11
 * while the project targets JVM 17). Status: COMPLETE_NOT_RUN — pending CI.
 */
class SourceLanguagesTest {

    @Test
    fun `blank and null dropdown values map to null api code (auto-detect)`() {
        assertNull(SourceLanguages.toApiCode(""))
        assertNull(SourceLanguages.toApiCode(null))
        assertNull(SourceLanguages.toApiCode("   "))
        assertTrue(SourceLanguages.isAuto(""))
        assertTrue(SourceLanguages.isAuto(null))
        assertFalse(SourceLanguages.isAuto("en"))
    }

    @Test
    fun `explicit code is trimmed and passed through`() {
        assertEquals("en", SourceLanguages.toApiCode("en"))
        assertEquals("ar", SourceLanguages.toApiCode(" ar "))
    }

    @Test
    fun `normalize lowercases two-letter codes and rejects malformed values`() {
        assertEquals("en", SourceLanguages.normalize("EN"))
        assertEquals("zh", SourceLanguages.normalize("zh"))
        assertNull(SourceLanguages.normalize(null))
        assertNull(SourceLanguages.normalize(""))
        assertNull(SourceLanguages.normalize("eng"))
        assertNull(SourceLanguages.normalize("e1"))
    }

    @Test
    fun `dropdown entries start with auto and contain all supported codes`() {
        val entries = SourceLanguages.dropdownEntries()
        assertEquals(SourceLanguages.AUTO, entries.first().first)
        assertEquals(SourceLanguages.SUPPORTED.size + 1, entries.size)
        assertEquals(SourceLanguages.SUPPORTED, entries.drop(1).map { it.first })
    }

    @Test
    fun `normalize rejects region tags and non letters`() {
        assertNull(SourceLanguages.normalize("en-US"))
        assertNull(SourceLanguages.normalize("12"))
        assertEquals("zh", SourceLanguages.normalize("ZH"))
    }
}
