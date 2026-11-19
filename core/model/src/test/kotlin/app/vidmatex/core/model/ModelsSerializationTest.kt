package app.vidmatex.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serialization/default verification for the non-spec models: media tracks, subtitle
 * tracks/cues, embed style, metadata, download item, and the non-secret key reference.
 * [DownloadSpecSerializationTest] covers DownloadSpec round-trips separately.
 */
class ModelsSerializationTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test fun `MediaTrack defaults and quality labels`() {
        val video = MediaTrack(trackId = "v1", kind = MediaKind.VIDEO, container = "mp4", heightPx = 1080, fps = 30)
        assertFalse(video.resumable)
        assertEquals("1080p30", video.qualityLabel)
        val audio = MediaTrack(trackId = "a1", kind = MediaKind.AUDIO, container = "m4a")
        assertEquals("M4A", audio.qualityLabel)

        val decoded = json.decodeFromString(MediaTrack.serializer(), json.encodeToString(MediaTrack.serializer(), video))
        assertEquals("v1", decoded.trackId)
        assertEquals(1080, decoded.heightPx)
        assertEquals(MediaKind.VIDEO, decoded.kind)
    }

    @Test fun `SubtitleTrack defaults and display label`() {
        val track = SubtitleTrack("s1", "ar", "Arabic", SubtitleKind.OFFICIAL)
        assertEquals(SubtitleFormat.VTT, track.defaultFormat)
        assertTrue(track.isTranslatable)
        assertEquals("Arabic — Official", track.displayLabel)

        val decoded = json.decodeFromString(SubtitleTrack.serializer(), json.encodeToString(SubtitleTrack.serializer(), track))
        assertEquals("ar", decoded.languageCode)
        assertEquals(SubtitleKind.OFFICIAL, decoded.kind)
    }

    @Test fun `SubtitleCue round-trips`() {
        val cue = SubtitleCue(index = 1, startMs = 0L, endMs = 2_000L, lines = listOf("line one", "line two"))
        val decoded = json.decodeFromString(SubtitleCue.serializer(), json.encodeToString(SubtitleCue.serializer(), cue))
        assertEquals(2_000L, decoded.endMs)
        assertEquals(2, decoded.lines.size)
    }

    @Test fun `EmbedStyle keeps stable defaults`() {
        val style = EmbedStyle()
        assertEquals(24, style.fontSize)
        val decoded = json.decodeFromString(EmbedStyle.serializer(), json.encodeToString(EmbedStyle.serializer(), style))
        assertEquals(style, decoded)
    }

    @Test fun `VideoMetadata nullable fields survive round-trip`() {
        val meta = VideoMetadata(
            sourceType = SourceType.YOUTUBE,
            sourceUrl = "https://youtu.be/x",
            sourceId = "x",
            title = "Title",
        )
        val decoded = json.decodeFromString(VideoMetadata.serializer(), json.encodeToString(VideoMetadata.serializer(), meta))
        assertNull(decoded.author)
        assertNull(decoded.durationSec)
        assertNull(decoded.thumbnailUrl)
        assertEquals("Title", decoded.title)
    }

    @Test fun `DownloadItem defaults and round-trip`() {
        val item = DownloadItem(
            id = "d1",
            sourceUrl = "https://example.com/v.mp4",
            sourceType = SourceType.DIRECT_URL,
            title = "V",
        )
        assertEquals(DownloadState.QUEUED, item.state)
        assertEquals(0, item.progressPercent)
        assertEquals(0L, item.downloadedBytes)
        assertNull(item.filePath)

        val decoded = json.decodeFromString(DownloadItem.serializer(), json.encodeToString(DownloadItem.serializer(), item))
        assertEquals("d1", decoded.id)
        assertEquals(SourceType.DIRECT_URL, decoded.sourceType)
    }

    @Test fun `enum names remain stable for persistence`() {
        assertEquals("VIDEO_ONLY", DownloadMode.VIDEO_ONLY.name)
        assertEquals("SUBTITLE_ONLY", DownloadMode.SUBTITLE_ONLY.name)
        assertEquals("VIDEO_AND_SUBTITLE", DownloadMode.VIDEO_AND_SUBTITLE.name)
        assertEquals("AUDIO_ONLY", DownloadMode.AUDIO_ONLY.name)
        assertEquals("OFFICIAL", SubtitleKind.OFFICIAL.name)
        assertEquals("AUTOMATIC", SubtitleKind.AUTOMATIC.name)
        assertEquals("SRT", SubtitleFormat.SRT.name)
        assertEquals("VIDEO", MediaKind.VIDEO.name)
        assertEquals("QUEUED", DownloadState.QUEUED.name)
        assertEquals("NONE", EmbedMode.NONE.name)
        // valueOf round-trip guards against renames breaking old rows.
        assertEquals(DownloadMode.AUDIO_ONLY, DownloadMode.valueOf("AUDIO_ONLY"))
        assertEquals(SubtitleKind.HUMAN, SubtitleKind.valueOf("HUMAN"))
    }

    @Test fun `SecretKeyRef carries an alias only and round-trips`() {
        val ref = SecretKeyRef(alias = "resolver_api_key", present = true)
        val decoded = json.decodeFromString(SecretKeyRef.serializer(), json.encodeToString(SecretKeyRef.serializer(), ref))
        assertEquals("resolver_api_key", decoded.alias)
        assertTrue(decoded.present)
    }
}
