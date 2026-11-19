package app.vidmatex.core.database

import androidx.room.TypeConverter
import app.vidmatex.core.model.DownloadState
import app.vidmatex.core.model.MediaKind
import app.vidmatex.core.model.SourceType
import app.vidmatex.core.model.SubtitleKind

/** Enums <-> TEXT. Unknown values fall back to safe defaults so a corrupt row never crashes queries. */
class Converters {
    @TypeConverter
    fun sourceTypeToString(value: SourceType): String = value.name
    @TypeConverter
    fun stringToSourceType(value: String): SourceType =
        runCatching { SourceType.valueOf(value) }.getOrDefault(SourceType.UNKNOWN)

    @TypeConverter
    fun downloadStateToString(value: DownloadState): String = value.name
    @TypeConverter
    fun stringToDownloadState(value: String): DownloadState =
        runCatching { DownloadState.valueOf(value) }.getOrDefault(DownloadState.QUEUED)

    @TypeConverter
    fun subtitleKindToString(value: SubtitleKind): String = value.name
    @TypeConverter
    fun stringToSubtitleKind(value: String): SubtitleKind =
        // AUTOMATIC is the neutral fallback: an unrecognized label must never be promoted
        // to human/OFFICIAL content.
        runCatching { SubtitleKind.valueOf(value) }.getOrDefault(SubtitleKind.AUTOMATIC)

    @TypeConverter
    fun mediaKindToString(value: MediaKind): String = value.name
    @TypeConverter
    fun stringToMediaKind(value: String): MediaKind =
        runCatching { MediaKind.valueOf(value) }.getOrDefault(MediaKind.VIDEO)
}
