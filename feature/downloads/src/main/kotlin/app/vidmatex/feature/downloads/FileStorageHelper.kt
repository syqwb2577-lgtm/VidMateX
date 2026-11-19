package app.vidmatex.feature.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import app.vidmatex.core.model.AppSettings
import app.vidmatex.core.model.SubtitleFormat
import java.io.File

/**
 * Moves a finished temp file from cacheDir to its final location and returns the stored
 * location string (a content:// URI for MediaStore/SAF, or an absolute file path on API < 29).
 *
 * Priority:
 *  1. SAF tree URI (settings.downloadDirUri) — user-chosen location, persistable permission.
 *  2. MediaStore Downloads collection with RELATIVE_PATH = Download/VidMateX (API 29+).
 *  3. App-private external files directory `getExternalFilesDir(DIRECTORY_DOWNLOADS)/VidMateX`
 *     (API < 29); no legacy broad-storage permission and no writes to shared public storage.
 */
internal object FileStorageHelper {

    private const val SUBDIR = "VidMateX"

    fun finalizeFile(
        context: Context,
        settings: AppSettings,
        tempFile: File,
        displayName: String,
        mimeType: String,
    ): String {
        // Defense-in-depth: sanitize the final name here even though the worker already
        // sanitized the title; blocks traversal/control chars and executable extensions.
        val safeName = sanitizeDisplayName(displayName)
        val treeUri = settings.downloadDirUri
        if (!treeUri.isNullOrBlank()) {
            runCatching { finalizeViaSaf(context, Uri.parse(treeUri), tempFile, safeName, mimeType) }
                .onSuccess { return it }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            finalizeViaMediaStore(context, tempFile, safeName, mimeType)
        } else {
            finalizeViaLegacyFile(context, tempFile, safeName)
        }
    }

    /** Pure: sanitizes a final display name (`base.ext`) via [FileNamePolicy]. */
    internal fun sanitizeDisplayName(displayName: String): String {
        val base = displayName.substringBeforeLast('.')
        val ext = if (displayName.contains('.')) displayName.substringAfterLast('.') else ""
        return FileNamePolicy.safeDisplayName(base, ext, "mp4")
    }

    private fun finalizeViaSaf(
        context: Context,
        treeUri: Uri,
        tempFile: File,
        displayName: String,
        mimeType: String,
    ): String {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Could not open SAF tree (persistable permission missing?)")
        val doc = tree.createFile(mimeType, displayName)
            ?: error("Could not create file in chosen folder")
        context.contentResolver.openOutputStream(doc.uri, "w")?.use { out ->
            tempFile.inputStream().use { it.copyTo(out) }
        } ?: error("Could not open SAF output stream")
        return doc.uri.toString()
    }

    private fun finalizeViaMediaStore(
        context: Context,
        tempFile: File,
        displayName: String,
        mimeType: String,
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR,
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            } ?: error("Could not open MediaStore output stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return uri.toString()
    }

    /**
     * API < 29 finalization: the app-private external files directory
     * (`getExternalFilesDir(DIRECTORY_DOWNLOADS)/VidMateX`). No broad storage permission and
     * nothing written to the world-readable public Downloads directory; the file remains
     * shareable/openable through the module's FileProvider.
     */
    private fun finalizeViaLegacyFile(context: Context, tempFile: File, displayName: String): String {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val dir = File(baseDir, SUBDIR)
        if (!dir.exists() && !dir.mkdirs()) error("Could not create app download directory")
        val out = File(dir, displayName)
        tempFile.copyTo(out, overwrite = true)
        return out.absolutePath
    }

    /** Delete a stored file by its location string (content URI or absolute path). Best-effort. */
    fun deleteStoredPath(context: Context, path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            if (path.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(path), null, null)
            } else {
                File(path).takeIf { it.exists() }?.delete()
            }
        }
    }

    /** Remove per-item temp files left in cacheDir (video/audio/subtitle/mux/embed stages).
     *  Only deletes `$itemId.*.tmp` files — never final files.
     *
     *  Policy (contract task 6):
     *  - success / cancel / permanent failure → every temp suffix is removed;
     *  - transient failure (retryable) on a server that accepted Range (`keepPartial=true`)
     *    → the partial stream temp(s) are KEPT so the retry can resume with a Range request;
     *    processing-stage temps (mux/sub/embed/orig sidecar) are always removed because they
     *    are derived artifacts, never resume sources.
     */
    fun cleanTempFiles(context: Context, itemId: String) {
        tempSuffixes(keepPartial = false).forEach { suffix ->
            runCatching { File(context.cacheDir, "$itemId.$suffix.tmp").takeIf { it.exists() }?.delete() }
        }
    }

    /** Failure-path cleanup: derived temps always deleted; stream partials kept only [keepPartial]. */
    fun cleanupForFailure(context: Context, itemId: String, keepPartial: Boolean) {
        tempSuffixes(keepPartial).forEach { suffix ->
            runCatching { File(context.cacheDir, "$itemId.$suffix.tmp").takeIf { it.exists() }?.delete() }
        }
    }

    /** Pure: the `.tmp` suffixes to remove under each cleanup policy. Tested in FileStorageHelperTest. */
    internal fun tempSuffixes(keepPartial: Boolean): List<String> {
        val derived = listOf("sub", "sub.orig", "mux", "embed")
        val streams = listOf("video", "audio")
        return if (keepPartial) derived else derived + streams
    }

    /** Audio-only MIME from the final extension (see [DownloadKinds.audioExtension]). */
    fun audioMime(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "ogg", "opus", "oga" -> "audio/ogg"
        "wav", "wave" -> "audio/wav"
        "flac" -> "audio/flac"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }

    fun subtitleExtension(format: SubtitleFormat): String = when (format) {
        SubtitleFormat.SRT -> "srt"
        SubtitleFormat.VTT -> "vtt"
        SubtitleFormat.ASS -> "ass"
    }

    fun subtitleMime(format: SubtitleFormat): String = when (format) {
        SubtitleFormat.SRT -> "application/x-subrip"
        SubtitleFormat.VTT -> "text/vtt"
        SubtitleFormat.ASS -> "text/plain"
    }

    fun mediaMime(container: String): String = when (container.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}
