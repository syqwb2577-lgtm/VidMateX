package app.vidmatex.feature.subtitles.transcription

import android.content.Context
import android.net.Uri
import app.vidmatex.core.common.AppResult
import app.vidmatex.core.model.SubtitleCue
import app.vidmatex.core.model.SubtitleFormat
import app.vidmatex.core.model.SubtitleKind
import app.vidmatex.media.processor.SubtitleParser
import java.io.File

/**
 * Importer for two separate flows:
 *  1. `copyToCache` — media/audio/video picked for Whisper transcription: the content
 *     URI is copied into app-private cache (`cacheDir/transcription_in/<jobId>.<ext>`)
 *     before the worker runs, because the worker reads absolute file paths and the
 *     picker grant may not survive a worker restart.
 *  2. `importSubtitle` — a standalone subtitle sidecar file (`.srt`/`.vtt`/`.ass`):
 *     extension validation via pure [SubtitleFileTypes], UTF-8 text read, then parsing
 *     delegated to the shared `media:processor` [SubtitleParser]. Successful imports
 *     carry provenance [SubtitleFileTypes.IMPORTED_PROVENANCE] ([SubtitleKind.HUMAN])
 *     so UI labels never relabel a user file as official or auto-generated.
 *
 * Android-specific (uses [Context.contentResolver]); pure validation/format mapping
 * lives in [SubtitleFileTypes] so the rules are covered by plain JVM unit tests.
 */
object TranscriptionFileImporter {
    fun copyToCache(context: Context, uri: Uri, jobId: String): File? {
        val dir = File(TranscriptionPaths.inputCacheDir(context.cacheDir.absolutePath))
        if (!dir.exists() && !dir.mkdirs()) return null
        val ext = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
            ?: "dat"
        val out = File(dir, "$jobId.$ext")
        return runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "openInputStream returned null for $uri" }
                out.outputStream().buffered().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            out.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()
    }

    /** Parsed result of a successfully imported standalone subtitle file. */
    data class ImportedSubtitle(
        val cues: List<SubtitleCue>,
        val format: SubtitleFormat,
        val sourceName: String,
        val kind: SubtitleKind = SubtitleFileTypes.IMPORTED_PROVENANCE,
    )

    /**
     * Validates the extension, reads the UTF-8 text, and parses through the shared
     * parser. Returns null when the extension is unsupported, the stream is unreadable,
     * or parsing yields no cues; the ViewModel surfaces a clear error in those cases.
     */
    fun importSubtitle(
        context: Context,
        uri: Uri,
        parser: SubtitleParser,
    ): ImportedSubtitle? {
        val displayName = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        val format = SubtitleFileTypes.formatForExtension(displayName) ?: return null
        val text = runCatching {
            context.contentResolver.openInputStream(uri).use { stream ->
                stream?.bufferedReader(Charsets.UTF_8)?.readText()
            }
        }.getOrNull()
        if (text.isNullOrBlank()) return null
        return when (val parsed = parser.parse(format, text)) {
            is AppResult.Success -> {
                if (parsed.value.isEmpty()) return null
                ImportedSubtitle(
                    cues = parsed.value,
                    format = format,
                    sourceName = displayName.ifBlank { "imported.$format" },
                )
            }
            is AppResult.Error -> null
        }
    }
}
