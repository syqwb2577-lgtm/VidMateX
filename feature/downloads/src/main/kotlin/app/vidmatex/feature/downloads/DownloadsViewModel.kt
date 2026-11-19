package app.vidmatex.feature.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vidmatex.core.common.DispatcherProvider
import app.vidmatex.core.common.DownloadManager
import app.vidmatex.core.model.DownloadItem
import app.vidmatex.core.model.DownloadState
import app.vidmatex.feature.downloads.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DownloadFilter(val labelRes: Int) {
    ALL(R.string.dl_filter_all),
    ACTIVE(R.string.dl_filter_active),
    COMPLETED(R.string.dl_filter_completed),
    FAILED(R.string.dl_filter_failed),
    PAUSED(R.string.dl_filter_paused);

    fun matches(state: DownloadState): Boolean = when (this) {
        ALL -> true
        ACTIVE -> state == DownloadState.QUEUED || state == DownloadState.ACTIVE
        COMPLETED -> state == DownloadState.COMPLETED
        FAILED -> state == DownloadState.FAILED
        PAUSED -> state == DownloadState.PAUSED
    }
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadManager: DownloadManager,
    @ApplicationContext private val context: Context,
    dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _filter = MutableStateFlow(DownloadFilter.ALL)
    val filter: StateFlow<DownloadFilter> = _filter

    val items: StateFlow<List<DownloadItem>> =
        downloadManager.observeAll()
            .combine(_filter) { list, f -> list.filter { f.matches(it.state) } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    /** Becomes false after the first items emission so the screen can show its empty state. */
    private val _waitingFirstSnapshot = MutableStateFlow(true)

    /**
     * G-3 top-level loading signal: true while the first flow snapshot has not arrived,
     * or while at least one job is QUEUED/ACTIVE. The screen renders a centered spinner
     * while this is true and the list is empty.
     */
    val isLoading: StateFlow<Boolean> =
        combine(items, _waitingFirstSnapshot) { list, waiting ->
            waiting || list.any { it.state == DownloadState.QUEUED || it.state == DownloadState.ACTIVE }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    /** Failed/cancelled rows whose error text the user dismissed this session (in-memory). */
    private val _dismissedErrors = MutableStateFlow<Set<String>>(emptySet())
    val dismissedErrors: StateFlow<Set<String>> = _dismissedErrors.asStateFlow()

    init {
        viewModelScope.launch {
            items.first()
            _waitingFirstSnapshot.value = false
        }
    }

    /** G-3: locally suppress a failed row's error message; the persisted row is untouched. */
    fun dismissError(id: String) {
        _dismissedErrors.value = _dismissedErrors.value + id
    }

    fun setFilter(filter: DownloadFilter) { _filter.value = filter }

    /** Guards rapid repeated taps (pause/resume/cancel/retry) that could enqueue duplicates. */
    private val actionBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun guarded(block: suspend () -> Unit) = viewModelScope.launch {
        if (!actionBusy.compareAndSet(false, true)) return@launch
        try { block() } finally { actionBusy.set(false) }
    }
    fun pause(id: String) = guarded { downloadManager.pause(id) }
    fun resume(id: String) = guarded { downloadManager.resume(id) }
    fun cancel(id: String) = guarded { downloadManager.cancel(id) }
    fun retry(id: String) = guarded { downloadManager.retry(id) }
    fun delete(id: String) = guarded { downloadManager.delete(id, deleteFile = true) }
    fun clearHistory() = guarded { downloadManager.clearHistory() }

    /** Open the finished file with the system viewer. Returns false if nothing can open it. */
    fun open(item: DownloadItem): Boolean {
        val uri = contentUriFor(item) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeFor(item.filePath))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Share the finished file via the system share sheet. */
    fun share(item: DownloadItem): Boolean {
        val uri = contentUriFor(item) ?: return false
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeFor(item.filePath))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val chooser = Intent.createChooser(intent, item.title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(chooser) }.isSuccess
    }

    private fun contentUriFor(item: DownloadItem): Uri? {
        val path = item.filePath ?: return null
        return if (path.startsWith("content://")) {
            Uri.parse(path)
        } else {
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
            }.getOrNull()
        }
    }

    private fun mimeFor(path: String?): String {
        val ext = path?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            else -> "*/*"
        }
    }
}
