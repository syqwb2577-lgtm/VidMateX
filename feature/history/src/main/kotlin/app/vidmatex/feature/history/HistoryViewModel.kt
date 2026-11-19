package app.vidmatex.feature.history

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vidmatex.core.common.DownloadManager
import app.vidmatex.core.model.DownloadItem
import app.vidmatex.core.model.DownloadState
import app.vidmatex.feature.history.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the History screen. */
data class HistoryUiState(
    val items: List<DownloadItem> = emptyList(),
    val showFailed: Boolean = false,
    /** True while the underlying download flow has not emitted its first snapshot yet. */
    val isLoading: Boolean = false,
)

/** One-shot events from ViewModel to UI (toasts, etc.). */
sealed interface HistoryEvent {
    data class Toast(val message: String) : HistoryEvent
}

/**
 * History of downloads: active, queued, paused, completed, and (when [showFailed] is on)
 * failed/cancelled attempts. Defaults to ongoing + finished records; the toggle also
 * includes FAILED/CANCELLED rows so the user can retry or remove them.
 *
 * Actions:
 *  - [delete] removes the record (and the file on disk, by default).
 *  - [retry] re-enqueues a FAILED/CANCELLED record via [DownloadManager.retry].
 *  - [open] launches an [Intent.ACTION_VIEW] for the downloaded file.
 *  - [share] launches an [Intent.ACTION_SEND] chooser for the file.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val downloadManager: DownloadManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _showFailed = MutableStateFlow(false)
    val showFailed: StateFlow<Boolean> = _showFailed

    /** Becomes false after the first snapshot arrives so the screen can distinguish
     *  "still loading" from a genuinely empty list. */
    private val _loading = MutableStateFlow(true)

    val uiState: StateFlow<HistoryUiState> =
        combine(downloadManager.observeAll(), _showFailed, _loading) { all, showFailed, loading ->
            val wanted = if (showFailed) {
                setOf(
                    DownloadState.QUEUED,
                    DownloadState.ACTIVE,
                    DownloadState.PAUSED,
                    DownloadState.COMPLETED,
                    DownloadState.FAILED,
                    DownloadState.CANCELLED,
                )
            } else {
                setOf(
                    DownloadState.QUEUED,
                    DownloadState.ACTIVE,
                    DownloadState.PAUSED,
                    DownloadState.COMPLETED,
                )
            }
            HistoryUiState(
                items = all.filter { it.state in wanted }
                    .sortedByDescending { it.createdAtMs },
                showFailed = showFailed,
                isLoading = loading,
            )
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = HistoryUiState(isLoading = true),
            )

    private val _events = MutableSharedFlow<HistoryEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<HistoryEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            downloadManager.observeAll().collect { _loading.value = false }
        }
    }

    fun toggleShowFailed() = _showFailed.update { !it }

    fun delete(id: String) {
        viewModelScope.launch { downloadManager.delete(id, deleteFile = true) }
    }

    /** Re-queue a failed/cancelled download. [DownloadManager.retry] exists on the
     *  interface; failures surface as a toast event from the manager implementation. */
    fun retry(id: String) {
        viewModelScope.launch {
            downloadManager.retry(id)
            _events.tryEmit(HistoryEvent.Toast(context.getString(R.string.history_retry_queued)))
        }
    }

    fun open(item: DownloadItem) {
        val path = item.filePath
        if (path.isNullOrBlank()) {
            _events.tryEmit(HistoryEvent.Toast(context.getString(R.string.history_toast_file_missing)))
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(path), guessMime(path))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            _events.tryEmit(HistoryEvent.Toast(context.getString(R.string.history_toast_no_open_app)))
        }
    }

    fun share(item: DownloadItem) {
        val path = item.filePath
        if (path.isNullOrBlank()) {
            _events.tryEmit(HistoryEvent.Toast(context.getString(R.string.history_toast_file_missing)))
            return
        }
        val uri = Uri.parse(path)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = guessMime(path)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, item.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.history_share_subject, item.title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            _events.tryEmit(HistoryEvent.Toast(context.getString(R.string.history_toast_no_share_app)))
        }
    }

    private fun guessMime(path: String): String = when {
        path.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
        path.endsWith(".webm", ignoreCase = true) -> "video/webm"
        path.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
        path.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
        path.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
        path.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
        path.endsWith(".srt", ignoreCase = true) -> "application/x-subrip"
        path.endsWith(".vtt", ignoreCase = true) -> "text/vtt"
        path.endsWith(".ass", ignoreCase = true) -> "text/plain"
        else -> "*/*"
    }
}
