package app.vidmatex.feature.subtitles

import app.vidmatex.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [StandaloneSubtitleSpecBuilder] (C4 standalone subtitle export): title
 * synthesis, local-path vs synthetic `local://` source URL, and DIRECT_URL metadata shape.
 * No Android imports.
 *
 * Written but NOT executed (no Android SDK / Gradle in this static environment).
 * Status: COMPLETE_NOT_RUN — pending CI (`./gradlew :feature:subtitles:testDebugUnitTest`).
 */
class StandaloneSubtitleSpecBuilderTest {

    @Test
    fun title_usesFileNameWithoutExtension() {
        assertEquals(
            "lecture",
            StandaloneSubtitleSpecBuilder.title("/storage/emulated/0/Recordings/lecture.m4a"),
        )
        assertEquals(
            "clip",
            StandaloneSubtitleSpecBuilder.title("/tmp/clip"),
        )
    }

    @Test
    fun title_blankOrMissing_fallsBackToTranscription() {
        assertEquals("Transcription", StandaloneSubtitleSpecBuilder.title(null))
        assertEquals("Transcription", StandaloneSubtitleSpecBuilder.title(""))
        assertEquals("Transcription", StandaloneSubtitleSpecBuilder.title("   "))
        assertEquals("Transcription", StandaloneSubtitleSpecBuilder.title("/tmp/.hidden"))
    }

    @Test
    fun sourceUrl_prefersLocalPathWhenPresent() {
        val local = "/storage/emulated/0/Recordings/voice.mp3"
        assertEquals(local, StandaloneSubtitleSpecBuilder.sourceUrl(local, "job-1"))
    }

    @Test
    fun sourceUrl_syntheticLocalSchemeWhenNoPath() {
        val url = StandaloneSubtitleSpecBuilder.sourceUrl(null, "job-42")
        assertEquals("local://transcription/job-42", url)
        assertFalse(url.startsWith("http"))
    }

    @Test
    fun sourceUrl_syntheticPrefixEvenWithoutJobId() {
        val url = StandaloneSubtitleSpecBuilder.sourceUrl("", null)
        assertTrue(url.startsWith("local://transcription/"))
    }

    @Test
    fun metadata_isDirectUrlWithSynthesizedTitle() {
        val meta = StandaloneSubtitleSpecBuilder.buildMetadata(
            sourcePath = "/storage/emulated/0/notes/talk.wav",
            jobId = "job-7",
        )
        assertEquals(SourceType.DIRECT_URL, meta.sourceType)
        assertEquals("talk", meta.title)
        assertEquals("/storage/emulated/0/notes/talk.wav", meta.sourceUrl)
    }

    @Test
    fun metadata_withoutPath_usesSyntheticUrlAndFallbackTitle() {
        val meta = StandaloneSubtitleSpecBuilder.buildMetadata(sourcePath = null, jobId = "j9")
        assertEquals(SourceType.DIRECT_URL, meta.sourceType)
        assertEquals("Transcription", meta.title)
        assertEquals("local://transcription/j9", meta.sourceUrl)
        assertFalse(meta.sourceUrl.startsWith("http"))
    }

    @Test
    fun title_stripsOnlyTheFinalExtensionOfDottedFileNames() {
        assertEquals("archive.tar", StandaloneSubtitleSpecBuilder.title("/dl/archive.tar.gz"))
        assertEquals("recording", StandaloneSubtitleSpecBuilder.title("recording.m4a"))
        assertEquals("Transcription", StandaloneSubtitleSpecBuilder.title(null))
    }
}
