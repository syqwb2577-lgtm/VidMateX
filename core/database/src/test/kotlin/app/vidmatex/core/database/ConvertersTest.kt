package app.vidmatex.core.database

import app.vidmatex.core.model.DownloadState
import app.vidmatex.core.model.MediaKind
import app.vidmatex.core.model.SourceType
import app.vidmatex.core.model.SubtitleKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [Converters]: enum <-> TEXT round-trips and safe fallbacks for
 * unknown/corrupt stored values (no Room runtime required to exercise the functions).
 */
class ConvertersTest {
    private val converters = Converters()

    @Test fun `SourceType round-trips and unknown falls back to UNKNOWN`() {
        assertEquals(SourceType.TIKTOK.name, converters.sourceTypeToString(SourceType.TIKTOK))
        assertEquals(SourceType.TIKTOK, converters.stringToSourceType("TIKTOK"))
        assertEquals(SourceType.UNKNOWN, converters.stringToSourceType("NO_SUCH_SOURCE"))
    }

    @Test fun `DownloadState round-trips and unknown falls back to QUEUED`() {
        assertEquals(DownloadState.PAUSED.name, converters.downloadStateToString(DownloadState.PAUSED))
        assertEquals(DownloadState.PAUSED, converters.stringToDownloadState("PAUSED"))
        assertEquals(DownloadState.QUEUED, converters.stringToDownloadState("NO_SUCH_STATE"))
    }

    @Test fun `SubtitleKind round-trips and unknown falls back to neutral AUTOMATIC`() {
        assertEquals(SubtitleKind.HUMAN.name, converters.subtitleKindToString(SubtitleKind.HUMAN))
        assertEquals(SubtitleKind.HUMAN, converters.stringToSubtitleKind("HUMAN"))
        assertEquals(SubtitleKind.AUTOMATIC, converters.stringToSubtitleKind("NO_SUCH_KIND"))
    }

    @Test fun `MediaKind round-trips and unknown falls back to VIDEO`() {
        assertEquals(MediaKind.AUDIO.name, converters.mediaKindToString(MediaKind.AUDIO))
        assertEquals(MediaKind.AUDIO, converters.stringToMediaKind("AUDIO"))
        assertEquals(MediaKind.VIDEO, converters.stringToMediaKind("NO_SUCH_KIND"))
    }

    @Test fun `all SourceType values round-trip through TEXT`() {
        for (value in SourceType.entries) {
            assertEquals(value, converters.stringToSourceType(converters.sourceTypeToString(value)))
        }
    }

    @Test fun `all DownloadState values round-trip through TEXT`() {
        for (value in DownloadState.entries) {
            assertEquals(value, converters.stringToDownloadState(converters.downloadStateToString(value)))
        }
    }

    @Test fun `all SubtitleKind values round-trip and never promote unknown to OFFICIAL or HUMAN`() {
        for (value in SubtitleKind.entries) {
            assertEquals(value, converters.stringToSubtitleKind(converters.subtitleKindToString(value)))
        }
        val fallback = converters.stringToSubtitleKind("CORRUPTED_LABEL")
        assertEquals(SubtitleKind.AUTOMATIC, fallback)
    }

    @Test fun `empty and blank stored values fall back to safe defaults`() {
        assertEquals(SourceType.UNKNOWN, converters.stringToSourceType(""))
        assertEquals(DownloadState.QUEUED, converters.stringToDownloadState("   "))
        assertEquals(SubtitleKind.AUTOMATIC, converters.stringToSubtitleKind(""))
        assertEquals(MediaKind.VIDEO, converters.stringToMediaKind(""))
    }
}
