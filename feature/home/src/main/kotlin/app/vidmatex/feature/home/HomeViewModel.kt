package app.vidmatex.feature.home

import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vidmatex.core.common.AppResult
import app.vidmatex.core.common.DispatcherProvider
import app.vidmatex.core.common.DownloadManager
import app.vidmatex.core.common.SettingsRepository
import app.vidmatex.core.common.UrlValidator
import app.vidmatex.core.model.DownloadMode
import app.vidmatex.core.model.DownloadSpec
import app.vidmatex.core.model.EmbedMode
import app.vidmatex.core.model.MediaKind
import app.vidmatex.core.model.MediaTrack
import app.vidmatex.core.model.SubtitleTrack
import app.vidmatex.feature.home.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val mediaSourceResolver: app.vidmatex.source.adapters.MediaSourceResolver,
    private val downloadManager: DownloadManager,
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** Simple click debounce: returns false if called again within [intervalMs]. */
    @Volatile private var lastActionMs = 0L
    private fun debounce(intervalMs: Long = 800): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastActionMs < intervalMs) return false
        lastActionMs = now
        return true
    }

    init {
        // Auto-paste offer: read clipboard once on init. If it holds a URL, surface a
        // "Paste from clipboard" button. We never auto-download from the clipboard.
        viewModelScope.launch(dispatchers.main) {
            val clip = readClipboardUrl()
            if (clip != null && clip.isNotBlank()) {
                _state.update { it.copy(clipboardUrl = clip) }
            }
        }
    }

    // ---- User events ----

    fun onLinkChange(link: String) {
        val trimmed = link.trim()
        val source = if (UrlValidator.isValid(trimmed)) UrlValidator.detectSource(trimmed) else null
        _state.update {
            it.copy(
                link = link,
                detectedSource = source,
                // Clear stale resolution when the link changes materially.
                metadata = if (link == it.link) it.metadata else null,
                error = null,
            )
        }
    }

    fun pasteFromClipboard() {
        val url = _state.value.clipboardUrl ?: readClipboardUrl() ?: return
        _state.update { it.copy(link = url, clipboardUrl = null, error = null) }
        onLinkChange(url)
        resolveLink(url)
    }

    fun analyze() {
        if (!debounce()) return
        val link = _state.value.link.trim()
        resolveLink(link)
    }

    fun selectQuality(track: MediaTrack) {
        _state.update { current ->
            if (track.kind == MediaKind.VIDEO) {
                // Pair video-only tracks with the best available audio track.
                val needsAudio = track.acodec.isNullOrBlank()
                val audio = if (needsAudio) bestAudio(current.audioTracks) else current.selectedAudio
                current.copy(selectedVideo = track, selectedAudio = audio, error = null)
            } else {
                current.copy(selectedAudio = track, error = null)
            }
        }
    }

    fun selectSubtitle(track: SubtitleTrack?) {
        _state.update { it.copy(selectedSubtitle = track, error = null) }
    }

    fun setMode(mode: DownloadMode) {
        _state.update { it.copy(mode = mode, error = null) }
    }

    /**
     * Audio-only toggle. When on, the video track and subtitle selection are cleared;
     * the enqueued spec carries only the chosen audio track (worker-side audio-only
     * finalization; no video mux).
     */
    fun setAudioOnly(enabled: Boolean) {
        _state.update { cur ->
            cur.copy(
                audioOnly = enabled,
                selectedVideo = if (enabled) null else cur.videoTracks.firstOrNull(),
                selectedSubtitle = if (enabled) null else cur.selectedSubtitle,
                error = null,
            )
        }
    }

    fun dismissSnackbar() {
        _state.update { it.copy(snackbarMessage = null) }
    }
    /** G-3: clear the inline resolution/download error card without discarding the link. */
    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun download() {
        if (!debounce()) return
        val current = _state.value
        if (current.isEnqueuing) return // guard against duplicate taps / double enqueue
        if (!current.canDownload) {
            _state.update {
                it.copy(
                    error = if (current.audioOnly)
                        context.getString(R.string.home_error_audio_selection)
                    else
                        context.getString(R.string.home_error_no_selection)
                )
            }
            return
        }
        val metadata = current.metadata ?: return
        _state.update { it.copy(isEnqueuing = true, error = null) }
        viewModelScope.launch(dispatchers.io) {
            try {
                val settingsSnapshot = settings.current()
                val audioOnly = current.audioOnly
                // Explicit AUDIO_ONLY mode + additive audioOnly flag; the worker also keeps
                // the structural fallback (videoTrack == null, audioTrack != null) for older
                // serialized specs that lack the flag.
                val spec = DownloadSpec(
                    metadata = metadata,
                    mode = if (audioOnly) DownloadMode.AUDIO_ONLY else current.mode,
                    audioOnly = audioOnly,
                    videoTrack = if (audioOnly) null else current.selectedVideo,
                    audioTrack = current.selectedAudio,
                    subtitleTrack = if (audioOnly) null
                        else if (current.mode != DownloadMode.VIDEO_ONLY) current.selectedSubtitle else null,
                    embedMode = if (audioOnly) EmbedMode.NONE else settingsSnapshot.defaultEmbedMode,
                    outputFileName = sanitizeFileName(metadata.title),
                )
                when (val result = downloadManager.enqueue(spec)) {
                    is AppResult.Success -> _state.update {
                        it.copy(snackbarMessage = context.getString(R.string.home_download_started), error = null)
                    }
                    is AppResult.Error -> _state.update {
                        it.copy(error = context.getString(R.string.home_download_failed, result.message))
                    }
                }
            } finally {
                _state.update { it.copy(isEnqueuing = false) }
            }
        }
    }

    // ---- Internal resolution ----

    private fun resolveLink(rawLink: String) {
        if (!UrlValidator.isValid(rawLink)) {
            _state.update { it.copy(error = context.getString(R.string.home_error_invalid_url), isLoading = false) }
            return
        }
        val normalized = UrlValidator.normalize(rawLink)
        val adapter = when (val r = mediaSourceResolver.adapterFor(normalized)) {
            is AppResult.Success -> r.value
            is AppResult.Error -> {
                _state.update { it.copy(error = r.message, isLoading = false) }
                return
            }
        }

        _state.update {
            it.copy(
                isLoading = true,
                error = null,
                detectedSource = adapter.sourceType,
                metadata = null,
                videoTracks = emptyList(),
                audioTracks = emptyList(),
                subtitles = emptyList(),
                selectedVideo = null,
                selectedAudio = null,
                selectedSubtitle = null,
            )
        }

        viewModelScope.launch(dispatchers.io) {
            val metadata = when (val r = adapter.resolve(normalized)) {
                is AppResult.Success -> r.value
                is AppResult.Error -> {
                    _state.update { it.copy(isLoading = false, error = r.message) }
                    return@launch
                }
            }
            val formats = when (val r = adapter.listFormats(metadata)) {
                is AppResult.Success -> r.value
                is AppResult.Error -> {
                    _state.update { it.copy(isLoading = false, error = r.message, metadata = metadata) }
                    return@launch
                }
            }
            val subtitles = when (val r = adapter.listSubtitles(metadata)) {
                is AppResult.Success -> r.value
                is AppResult.Error -> emptyList() // subtitles are optional; don't fail the whole resolve
            }

            val videoTracks = formats.filter { it.kind == MediaKind.VIDEO }
                .sortedByDescending { it.heightPx ?: 0 }
            val audioTracks = formats.filter { it.kind == MediaKind.AUDIO }
                .sortedByDescending { it.bitrateBps ?: 0 }

            val prefs = settings.settings.first()
            val defaultVideo = videoTracks.firstOrNull { t ->
                prefs.defaultQualityHeight?.let { (t.heightPx ?: 0) <= it } == true
            } ?: videoTracks.firstOrNull()
            val defaultAudio = bestAudio(audioTracks)
            val defaultSubtitle = subtitles.firstOrNull { s ->
                prefs.defaultSubtitleLanguage?.let { s.languageCode.equals(it, ignoreCase = true) } == true
            }

            _state.update {
                it.copy(
                    isLoading = false,
                    metadata = metadata,
                    videoTracks = videoTracks,
                    audioTracks = audioTracks,
                    subtitles = subtitles,
                    selectedVideo = defaultVideo,
                    selectedAudio = defaultAudio,
                    selectedSubtitle = defaultSubtitle,
                    error = if (videoTracks.isEmpty() && audioTracks.isEmpty())
                        context.getString(R.string.home_error_no_tracks) else null,
                )
            }
        }
    }

    private fun bestAudio(tracks: List<MediaTrack>): MediaTrack? =
        tracks.maxByOrNull { it.bitrateBps ?: 0 }

    private fun readClipboardUrl(): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(context)?.toString()?.trim().orEmpty()
        return if (UrlValidator.isValid(text)) UrlValidator.normalize(text) else null
    }

    private fun sanitizeFileName(title: String): String {
        val cleaned = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_")
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(80)
        return cleaned.ifBlank { "video" }
    }
}
