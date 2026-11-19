package app.vidmatex.feature.downloads

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot "download just completed with a real local file" signal bus (task 3).
 *
 * `core/common.DownloadManager` (read-only) exposes only `observeAll()` / `observe()`
 * flows; it has no dedicated completed-callback. This in-process bus fills that gap
 * without changing the cross-module interface. [DownloadWorker] and [EmbedWorker] emit
 * exactly once per item at their final completion point (after `dao.markCompleted` and
 * `notifyCompleted`). The `:app` module's auto-transcribe observer collects [events]
 * and may enqueue transcription. Subscribers must be idempotent: a process restart
 * re-delivers nothing (replay = 0), and duplicate emissions are guarded by the observer's
 * in-memory dedup registry.
 *
 * Cross-module note: a durable "completed, not yet transcribed" flag would require a
 * `core/database` column (owned elsewhere); recorded in handoff/cross_cut_requests.md.
 */
@Singleton
class CompletedDownloadBus @Inject constructor() {

    data class Event(
        val downloadId: String,
        /** Final local path/URI as stored on the Download row (file://, absolute path, or content://). */
        val filePath: String,
        val subtitlePath: String?,
        val title: String,
        val sourceUrl: String,
        val completedAtMs: Long,
    )

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** Non-blocking emit; drops silently if the buffer is full (subscriber backpressure). */
    fun tryEmit(event: Event): Boolean = _events.tryEmit(event)
}
