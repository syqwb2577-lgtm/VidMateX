package app.vidmatex.feature.subtitles

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.vidmatex.core.common.AppResult
import app.vidmatex.core.common.DispatcherProvider
import app.vidmatex.core.common.DownloadManager
import app.vidmatex.core.common.SettingsRepository
import app.vidmatex.core.common.UrlValidator
import app.vidmatex.core.model.AppSettings
import app.vidmatex.core.model.DownloadMode
import app.vidmatex.core.model.DownloadSpec
import app.vidmatex.core.model.EmbedMode
import app.vidmatex.core.model.EmbedStyle
import app.vidmatex.core.model.MediaKind
import app.vidmatex.core.model.MediaTrack
import app.vidmatex.core.model.SubtitleCue
import app.vidmatex.core.model.SubtitleFormat
import app.vidmatex.core.model.SubtitleKind
import app.vidmatex.core.model.SubtitleTrack
import app.vidmatex.core.model.TranslationProvider
import app.vidmatex.core.model.VideoMetadata
import app.vidmatex.feature.subtitles.transcription.ActiveTranscriptionSet
import app.vidmatex.feature.subtitles.transcription.CompletedAudioHook
import app.vidmatex.feature.subtitles.transcription.ConfigSeverity
import app.vidmatex.feature.subtitles.transcription.SourceLanguages
import app.vidmatex.feature.subtitles.transcription.TranscriptionDedup
import app.vidmatex.feature.subtitles.transcription.TranscriptionFileImporter
import app.vidmatex.feature.subtitles.transcription.TranscriptionState
import app.vidmatex.feature.subtitles.transcription.TranscriptionStateMapper
import app.vidmatex.feature.subtitles.transcription.TranscriptionWork
import app.vidmatex.feature.subtitles.transcription.TranscriptionWorker
import app.vidmatex.feature.subtitles.transcription.WorkerRunState
import app.vidmatex.feature.subtitles.transcription.WhisperEndpointConfig
import app.vidmatex.feature.subtitles.transcription.WhisperRequestBuilder
import app.vidmatex.feature.subtitles.transcription.WhisperSecret
import app.vidmatex.media.processor.SettingsAwareSpeechToText
import app.vidmatex.media.processor.SpeechToText
import app.vidmatex.media.processor.SubtitleParser
import app.vidmatex.media.processor.TranslatorRegistry
import app.vidmatex.source.adapters.MediaSourceResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** Target languages offered in the dropdown (ML Kit + LibreTranslate both support these). */
val TARGET_LANGUAGES = listOf("ar", "en", "fr", "es", "de", "tr", "zh")

data class SubtitlesUiState(
    val inputUrl: String = "",
    val isResolving: Boolean = false,
    val metadata: VideoMetadata? = null,
    val videoTracks: List<MediaTrack> = emptyList(),
    val audioTracks: List<MediaTrack> = emptyList(),
    val selectedVideoTrack: MediaTrack? = null,
    val selectedAudioTrack: MediaTrack? = null,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val selectedTrack: SubtitleTrack? = null,
    val isFetchingSubtitle: Boolean = false,
    val cues: List<SubtitleCue> = emptyList(),
    val translatedCues: List<SubtitleCue>? = null,
    val isTranslating: Boolean = false,
    val translationMessage: String? = null,
    val subtitleFormat: SubtitleFormat = SubtitleFormat.SRT,
    val translateEnabled: Boolean = false,
    val targetLanguage: String = "en",
    /** User-applied timestamp offset applied to both original and translated cues before download. */
    val subtitleShiftMs: Long = 0L,
    /** When translation is used, also write the original subtitle as a separate sidecar file. */
    val saveOriginalSubtitleSidecar: Boolean = true,
    val downloadMode: DownloadMode = DownloadMode.VIDEO_AND_SUBTITLE,
    val embedMode: EmbedMode = EmbedMode.NONE,
    val embedStyle: EmbedStyle = EmbedStyle(),
    val isDownloading: Boolean = false,
    val downloadEnqueued: Boolean = false,
    val errorMessage: String? = null,
    val settings: AppSettings = AppSettings(),
    val providerConfigured: Boolean = false,
    /** True when a Whisper (SpeechToText) endpoint is configured in Settings. */
    val whisperAvailable: Boolean = false,
    // ---- Whisper transcription (WorkManager-backed; see transcription/ package) ----
    val transcriptionState: TranscriptionState = TranscriptionState.IDLE,
    val transcriptionJobId: String? = null,
    val transcriptionPath: String? = null,
    val transcriptionProgress: Int = 0,
    val transcriptionStage: String? = null,
    val transcriptionError: String? = null,
    val transcriptionCueCount: Int = 0,
    /** Explicit spoken-language dropdown value; "" = auto-detect (Whisper `language` omitted). */
    val sourceLanguage: String = "",
    /** Non-blocking config note (e.g. key absent for a keyless self-hosted endpoint). */
    val transcriptionKeyWarning: String? = null,
    /** Retained local path of the newest completed audio/download item (pick-to-transcribe). */
    val completedAudioPath: String? = null,
    /** Per-cue text overrides from the preview editor; keyed by cue index. */
    val cueOverrides: Map<Int, List<String>> = emptyMap(),
    /** Display name of the last imported standalone subtitle file (provenance = HUMAN). */
    val importedTrackName: String? = null,
) {
    val previewCues: List<SubtitleCue> get() = (translatedCues ?: cues).take(10)
    val totalCues: Int get() = (translatedCues ?: cues).size
    val canDownload: Boolean
        get() = (!isDownloading && !isResolving) && (
            metadata != null &&
                (downloadMode == DownloadMode.SUBTITLE_ONLY || selectedVideoTrack != null) &&
                (downloadMode == DownloadMode.VIDEO_ONLY || selectedTrack != null || cues.isNotEmpty())
        ) || (
            // C4: standalone SUBTITLE_ONLY export (transcription cues, no resolved link).
            downloadMode == DownloadMode.SUBTITLE_ONLY && cues.isNotEmpty() &&
                !isDownloading && !isResolving
        )
}

@HiltViewModel
class SubtitlesViewModel @Inject constructor(
    private val resolver: MediaSourceResolver,
    private val parser: SubtitleParser,
    private val translatorRegistry: TranslatorRegistry,
    private val downloadManager: DownloadManager,
    private val settingsRepository: SettingsRepository,
    private val speechToText: SpeechToText,
    private val dispatchers: DispatcherProvider,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SubtitlesUiState())
    val state: StateFlow<SubtitlesUiState> = _state.asStateFlow()

    /** Application-context WorkManager; observes transcription jobs by their tag. */
    private val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }
    /** One active dedup slot per source file hash/path; pruned on terminal states. */
    private val activeTranscriptionSet = ActiveTranscriptionSet()

    init {
        viewModelScope.launch(dispatchers.default) {
            settingsRepository.settings.collect { settings ->
                // Push the fresh endpoint snapshot into the binding so the non-suspend
                // `isAvailable()` reflects current settings (interface signature unchanged).
                (speechToText as? SettingsAwareSpeechToText)?.syncWithSettings(settings)
                val provider = translatorRegistry.forProvider(settings.translationProvider, settings)
                _state.value = _state.value.copy(
                    settings = settings,
                    providerConfigured = provider.isConfigured(),
                    whisperAvailable = speechToText.isAvailable(),
                    // If the provider changed and translation is on, drop stale translated preview.
                    translatedCues = _state.value.translatedCues,
                )
            }
        }
        // Observe transcription workers by tag; map WorkInfo state onto TranscriptionState.
        viewModelScope.launch(dispatchers.default) {
            workManager.getWorkInfosByTagFlow(TranscriptionWork.TAG).collect { infos ->
                handleTranscriptionWorkInfos(infos)
            }
        }
        // Completed-download hook (public DownloadManager API): keep the newest
        // COMPLETED audio/media local path available as a one-tap transcription source.
        // There is no dedicated completion callback in the downloads module yet
        // (cross-module follow-up); observeAll() + DownloadItem.filePath is the supported
        // consumption path.
        viewModelScope.launch(dispatchers.io) {
            downloadManager.observeAll().collect { items ->
                val latest = CompletedAudioHook.pickLatest(items)
                if (latest?.filePath != _state.value.completedAudioPath) {
                    _state.value = _state.value.copy(completedAudioPath = latest?.filePath)
                }
            }
        }
    }

    fun onUrlChange(url: String) {
        _state.value = _state.value.copy(inputUrl = url, errorMessage = null, downloadEnqueued = false)
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    /** G-3: re-run the last identifiable failed operation when the snackbar Retry action fires. */
    fun retryLastError() {
        val s = _state.value
        when (SubtitlesRetryPlanner.target(
            inputUrl = s.inputUrl,
            hasSelectedTrack = s.selectedTrack != null,
            hasCues = s.cues.isNotEmpty(),
            canDownload = s.canDownload,
            transcriptionFailed = s.transcriptionState == TranscriptionState.FAILED,
            transcriptionHasPath = !s.transcriptionPath.isNullOrBlank(),
        )) {
            SubtitlesRetryPlanner.RetryTarget.RESOLVE_LINK -> resolve()
            SubtitlesRetryPlanner.RetryTarget.REFETCH_TRACK ->
                s.selectedTrack?.let { selectTrack(it) } ?: resolve()
            SubtitlesRetryPlanner.RetryTarget.DOWNLOAD -> download()
            SubtitlesRetryPlanner.RetryTarget.RETRY_TRANSCRIPTION -> retryTranscription()
            SubtitlesRetryPlanner.RetryTarget.NONE -> clearError()
        }
    }

    fun reset() {
        _state.value = SubtitlesUiState(settings = _state.value.settings, providerConfigured = _state.value.providerConfigured)
    }

    // ---------------------------------------------------------- Step 1: resolve + list tracks

    fun resolve() {
        val url = _state.value.inputUrl.trim()
        if (!UrlValidator.isValid(url)) {
            _state.value = _state.value.copy(errorMessage = "That doesn't look like a valid link.")
            return
        }
        viewModelScope.launch(dispatchers.io) {
            _state.value = _state.value.copy(
                isResolving = true, errorMessage = null, downloadEnqueued = false,
                metadata = null, subtitleTracks = emptyList(), cues = emptyList(), translatedCues = null,
            )
            val adapter = when (val r = resolver.adapterFor(url)) {
                is AppResult.Success -> r.value
                is AppResult.Error -> {
                    _state.value = _state.value.copy(isResolving = false, errorMessage = r.message)
                    return@launch
                }
            }
            val metadata = when (val r = adapter.resolve(UrlValidator.normalize(url))) {
                is AppResult.Success -> r.value
                is AppResult.Error -> {
                    _state.value = _state.value.copy(isResolving = false, errorMessage = r.message)
                    return@launch
                }
            }
            val formats = adapter.listFormats(metadata).getOrNull().orEmpty()
            val subtitles = adapter.listSubtitles(metadata).getOrNull().orEmpty()
            val videos = formats.filter { it.kind == MediaKind.VIDEO }.sortedByDescending { it.heightPx ?: 0 }
            val audios = formats.filter { it.kind == MediaKind.AUDIO }.sortedByDescending { it.bitrateBps ?: 0 }
            _state.value = _state.value.copy(
                isResolving = false,
                metadata = metadata,
                videoTracks = videos,
                audioTracks = audios,
                selectedVideoTrack = videos.firstOrNull(),
                selectedAudioTrack = audios.firstOrNull(),
                subtitleTracks = subtitles,
                selectedTrack = subtitles.firstOrNull { it.kind != app.vidmatex.core.model.SubtitleKind.AUTOMATIC } ?: subtitles.firstOrNull(),
            )
            // Auto-load the default track's preview.
            _state.value.selectedTrack?.let { selectTrack(it) }
        }
    }

    // ---------------------------------------------------------- Step 2: select + fetch + preview

    fun selectTrack(track: SubtitleTrack) {
        val metadata = _state.value.metadata ?: return
        viewModelScope.launch(dispatchers.io) {
            _state.value = _state.value.copy(
                selectedTrack = track,
                isFetchingSubtitle = true,
                cues = emptyList(),
                translatedCues = null,
                translationMessage = null,
            )
            val adapter = when (val r = resolver.adapterFor(metadata.sourceUrl)) {
                is AppResult.Success -> r.value
                is AppResult.Error -> {
                    _state.value = _state.value.copy(isFetchingSubtitle = false, errorMessage = r.message)
                    return@launch
                }
            }
            val raw = when (val r = adapter.fetchSubtitle(metadata, track, track.defaultFormat)) {
                is AppResult.Success -> r.value
                is AppResult.Error -> {
                    _state.value = _state.value.copy(isFetchingSubtitle = false, errorMessage = r.message)
                    return@launch
                }
            }
            when (val parsed = parser.parse(track.defaultFormat, raw)) {
                is AppResult.Success -> _state.value = _state.value.copy(
                    isFetchingSubtitle = false,
                    cues = parsed.value,
                )
                is AppResult.Error -> _state.value = _state.value.copy(
                    isFetchingSubtitle = false,
                    errorMessage = "Could not parse subtitle: ${parsed.message}",
                )
            }
        }
    }

    // ---------------------------------------------------------- Step 3: format + translation

    fun setFormat(format: SubtitleFormat) {
        _state.value = _state.value.copy(subtitleFormat = format)
    }

    fun setTranslateEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(
            translateEnabled = enabled,
            translatedCues = if (!enabled) null else _state.value.translatedCues,
            translationMessage = null,
        )
    }

    fun setTargetLanguage(lang: String) {
        _state.value = _state.value.copy(targetLanguage = lang, translatedCues = null, translationMessage = null)
    }

    fun applyTranslation() {
        val s = _state.value
        if (s.cues.isEmpty()) {
            _state.value = _state.value.copy(translationMessage = "Load a subtitle track first.")
            return
        }
        val translator = translatorRegistry.forProvider(s.settings.translationProvider, s.settings)
        if (!translator.isConfigured()) {
            _state.value = _state.value.copy(
                translationMessage = "Translation provider is not configured. Enable ML Kit or set a LibreTranslate endpoint in Settings.",
            )
            return
        }
        viewModelScope.launch(dispatchers.io) {
            _state.value = _state.value.copy(isTranslating = true, translationMessage = null)
            val sourceLang = s.selectedTrack?.languageCode
            when (val result = translator.translate(s.cues, sourceLang, s.targetLanguage)) {
                is AppResult.Success -> _state.value = _state.value.copy(
                    isTranslating = false,
                    translatedCues = result.value,
                    translationMessage = "Translation applied to preview.",
                )
                is AppResult.Error -> _state.value = _state.value.copy(
                    isTranslating = false,
                    translationMessage = "Translation failed: ${result.message}",
                )
            }
        }
    }

    // ---------------------------------------------------------- Step 4/5: mode + embed + style

    fun setDownloadMode(mode: DownloadMode) {
        _state.value = _state.value.copy(downloadMode = mode)
    }

    fun setEmbedMode(mode: EmbedMode) {
        _state.value = _state.value.copy(embedMode = mode)
    }

    fun updateStyle(transform: (EmbedStyle) -> EmbedStyle) {
        _state.value = _state.value.copy(embedStyle = transform(_state.value.embedStyle))
    }

    /** Set the timestamp offset applied to every cue before download. Clamped to ±60s. */
    fun setShiftMs(ms: Long) {
        _state.value = _state.value.copy(subtitleShiftMs = ms.coerceIn(-60_000L, 60_000L))
    }

    fun setSaveOriginalSidecar(enabled: Boolean) {
        _state.value = _state.value.copy(saveOriginalSubtitleSidecar = enabled)
    }

    // ---------------------------------------------------------- Whisper transcription (WorkManager)
    /**
     * Real transcription entry point (replaces the placeholder message-only version).
     *
     * Path (b) (post-download audio file): when a local path is already attached in
     * state, enqueue the worker for it immediately.
     * Path (a) (picker): no local path yet -> move to [TranscriptionState.AWAITING_FILE];
     * the screen shows the OpenDocument picker, which calls [onAudioUriPicked].
     *
     * Availability mirrors [SpeechToText.isAvailable]: a valid configured endpoint is
     * required; otherwise a friendly FAILED error is surfaced without enqueuing work.
     */
    fun generateSubtitles(sourceLang: String? = null) {
        if (sourceLang != null) setSourceLanguage(sourceLang)
        if (!speechToText.isAvailable()) {
            _state.value = _state.value.copy(
                transcriptionState = TranscriptionState.FAILED,
                transcriptionError = appContext.getString(R.string.subtitles_transcribe_unconfigured),
            )
            return
        }
        val pending = _state.value.transcriptionPath
        if (!pending.isNullOrBlank()) {
            transcribeLocalFile(pending, _state.value.sourceLanguage)
        } else {
            _state.value = _state.value.copy(
                transcriptionState = TranscriptionState.AWAITING_FILE,
                transcriptionError = null,
                transcriptionProgress = 0,
                transcriptionStage = null,
            )
        }
    }

    /** Update the explicit spoken-language selection; blank/"" = auto-detect. */
    fun setSourceLanguage(code: String) {
        _state.value = _state.value.copy(sourceLanguage = code, translatedCues = null)
    }

    /**
     * Enqueue [TranscriptionWorker] for an already-local file. Used by the picker path
     * after [TranscriptionFileImporter] copies the URI into cache, and by the documented
     * post-download hook (CHANGES_REQUESTED.md §C2) once the downloads module exposes the
     * completed audio file path to this module.
     */
    fun transcribeLocalFile(path: String, sourceLang: String?) {
        if (!speechToText.isAvailable()) {
            _state.value = _state.value.copy(
                transcriptionState = TranscriptionState.FAILED,
                transcriptionError = appContext.getString(R.string.subtitles_transcribe_unconfigured),
            )
            return
        }
        // Pre-flight before any request: endpoint/model/file validation. The key lives
        // only in the in-memory [WhisperSecret]; a missing key is a soft warning
        // (keyless self-hosted endpoints are valid), invalid configs are blocking.
        val settings = _state.value.settings
        val file = File(path)
        val config = WhisperEndpointConfig(
            endpoint = settings.whisperEndpoint.trim(),
            model = WhisperRequestBuilder.DEFAULT_MODEL,
            secret = WhisperSecret.from(settings.whisperApiKey),
        )
        val validation = config.validate(file.exists(), if (file.exists()) file.length() else 0L)
        val blocking = validation.filter { it.severity == ConfigSeverity.BLOCKING }
        if (blocking.isNotEmpty()) {
            _state.value = _state.value.copy(
                transcriptionState = TranscriptionState.FAILED,
                transcriptionError = blocking.first().message,
            )
            return
        }
        val keyWarning = validation.firstOrNull { it.code == "missing_key" }?.message
        // Dedup: one active transcription job per source file key (hash of normalized path).
        val dedupKey = TranscriptionDedup.keyFor(path)
        if (dedupKey.isNotBlank() && activeTranscriptionSet.contains(dedupKey)) {
            _state.value = _state.value.copy(
                transcriptionState = TranscriptionState.FAILED,
                transcriptionError = appContext.getString(R.string.subtitles_transcribe_duplicate),
            )
            return
        }
        val jobId = _state.value.transcriptionJobId ?: UUID.randomUUID().toString()
        val lang = SourceLanguages.toApiCode(sourceLang ?: _state.value.sourceLanguage).orEmpty()
        _state.value = _state.value.copy(
            transcriptionState = TranscriptionState.PREPARING_FILE,
            transcriptionJobId = jobId,
            transcriptionPath = path,
            transcriptionProgress = 0,
            transcriptionStage = TranscriptionWorker.STAGE_PREPARING,
            transcriptionError = null,
            transcriptionKeyWarning = keyWarning,
        )
        viewModelScope.launch(dispatchers.io) {
            val added = dedupKey.isBlank() || activeTranscriptionSet.tryAdd(dedupKey)
            try {
                TranscriptionWork.enqueue(
                    context = appContext,
                    audioPath = path,
                    sourceLang = lang,
                    jobId = jobId,
                    requireWifi = settings.requireWifi,
                    dedupKey = dedupKey,
                )
                _state.value = _state.value.copy(transcriptionState = TranscriptionState.QUEUED)
            } catch (e: Throwable) {
                if (added) activeTranscriptionSet.remove(dedupKey)
                _state.value = _state.value.copy(
                    transcriptionState = TranscriptionState.FAILED,
                    transcriptionError = e.message ?: appContext.getString(R.string.subtitles_transcribe_unsupported),
                )
            }
        }
    }

    /**
     * Cancel the active job: WorkManager stops the coroutine, the worker deletes partial
     * sidecars in `onStopped`, and the active dedup slot is released for a later retry.
     */
    fun cancelTranscription() {
        val jobId = _state.value.transcriptionJobId
        if (jobId != null) TranscriptionWork.cancel(appContext, jobId)
        activeTranscriptionSet.remove(TranscriptionDedup.keyFor(_state.value.transcriptionPath.orEmpty()))
        _state.value = _state.value.copy(
            transcriptionState = TranscriptionState.CANCELED,
            transcriptionError = appContext.getString(R.string.subtitles_transcribe_canceled),
            transcriptionProgress = 0,
            transcriptionStage = null,
        )
    }

    /** One-tap transcription of the newest completed local audio/media download. */
    fun transcribeDownloadedAudio() {
        val path = _state.value.completedAudioPath ?: return
        transcribeLocalFile(path, null)
    }

    /**
     * Standalone subtitle import flow: validates extension, parses through the shared
     * `media:processor` parser, and installs a HUMAN-provenance track so the cues feed
     * the same preview -> translate -> shift -> export chain.
     */
    fun onSubtitleUriPicked(uri: Uri) {
        val imported = TranscriptionFileImporter.importSubtitle(appContext, uri, parser)
        if (imported == null) {
            _state.value = _state.value.copy(
                errorMessage = appContext.getString(R.string.subtitles_import_bad_file),
            )
            return
        }
        val track = SubtitleTrack(
            trackId = "imported_${imported.sourceName.hashCode()}",
            languageCode = parser.detectLanguage(imported.cues) ?: "und",
            languageName = imported.sourceName,
            kind = imported.kind,
            defaultFormat = imported.format,
        )
        val others = _state.value.subtitleTracks.filterNot { it.trackId == track.trackId }
        _state.value = _state.value.copy(
            cues = imported.cues,
            translatedCues = null,
            selectedTrack = track,
            subtitleTracks = others + track,
            importedTrackName = imported.sourceName,
            subtitleFormat = imported.format,
            errorMessage = null,
        )
    }

    /** Preview cue editor: override one cue's lines; blank input clears the override. */
    fun setCueOverride(index: Int, raw: String) {
        val lines = CueEdits.normalizeLines(raw)
        val next = _state.value.cueOverrides.toMutableMap()
        if (lines.isEmpty()) next.remove(index) else next[index] = lines
        _state.value = _state.value.copy(cueOverrides = next)
    }

    fun clearCueOverrides() {
        _state.value = _state.value.copy(cueOverrides = emptyMap())
    }

    /** Picker path (a): copy the granted content URI into app cache, then enqueue. */
    fun onAudioUriPicked(uri: Uri, sourceLang: String?) {
        if (!speechToText.isAvailable()) {
            _state.value = _state.value.copy(
                transcriptionState = TranscriptionState.FAILED,
                transcriptionError = appContext.getString(R.string.subtitles_transcribe_unconfigured),
            )
            return
        }
        val jobId = UUID.randomUUID().toString()
        _state.value = _state.value.copy(
            transcriptionState = TranscriptionState.PREPARING_FILE,
            transcriptionJobId = jobId,
            transcriptionError = null,
            transcriptionProgress = 0,
        )
        val file = TranscriptionFileImporter.copyToCache(appContext, uri, jobId)
        if (file == null) {
            _state.value = _state.value.copy(
                transcriptionState = TranscriptionState.FAILED,
                transcriptionError = appContext.getString(R.string.subtitles_transcribe_bad_file),
            )
            return
        }
        transcribeLocalFile(file.absolutePath, sourceLang ?: _state.value.sourceLanguage)
    }

    /** Re-run the retained local file after a transient failure (permanent errors also retryable manually). */
    fun retryTranscription() {
        val path = _state.value.transcriptionPath ?: return
        transcribeLocalFile(path, _state.value.sourceLanguage)
    }

    fun dismissTranscriptionError() {
        val resetWhenDismissed = setOf(TranscriptionState.FAILED, TranscriptionState.CANCELED)
        _state.value = _state.value.copy(
            transcriptionError = null,
            transcriptionState = if (_state.value.transcriptionState in resetWhenDismissed) {
                TranscriptionState.IDLE
            } else {
                _state.value.transcriptionState
            },
        )
    }

    /** Maps observed WorkInfo entries for the current job onto [TranscriptionState]. */
    private fun handleTranscriptionWorkInfos(infos: List<WorkInfo>) {
        val jobId = _state.value.transcriptionJobId ?: return
        // WorkInfo does not expose input data; match the job by its deterministic UUID
        // (TranscriptionWork.workIdFor derives the same UUID the enqueue path used).
        val expectedId = TranscriptionWork.workIdFor(jobId)
        val info = infos.firstOrNull { it.id == expectedId } ?: return
        val runState = when (info.state) {
            WorkInfo.State.ENQUEUED -> WorkerRunState.ENQUEUED
            WorkInfo.State.RUNNING -> WorkerRunState.RUNNING
            WorkInfo.State.SUCCEEDED -> WorkerRunState.SUCCEEDED
            WorkInfo.State.FAILED -> WorkerRunState.FAILED
            WorkInfo.State.CANCELLED -> WorkerRunState.CANCELLED
            WorkInfo.State.BLOCKED -> WorkerRunState.BLOCKED
            else -> WorkerRunState.ENQUEUED
        }
        when (runState) {
            WorkerRunState.ENQUEUED, WorkerRunState.BLOCKED ->
                _state.value = _state.value.copy(
                    transcriptionState = TranscriptionStateMapper.map(runState, _state.value.transcriptionState),
                    transcriptionError = null,
                )
            WorkerRunState.RUNNING -> {
                val progress = info.progress.getInt(TranscriptionWorker.PROGRESS_KEY, -1)
                val stage = info.progress.getString(TranscriptionWorker.STAGE_KEY)
                _state.value = _state.value.copy(
                    transcriptionState = TranscriptionStateMapper.map(runState, _state.value.transcriptionState),
                    transcriptionProgress = if (progress >= 0) progress else _state.value.transcriptionProgress,
                    transcriptionStage = stage ?: _state.value.transcriptionStage,
                    transcriptionError = null,
                )
            }
            WorkerRunState.SUCCEEDED -> onTranscriptionSucceeded(info)
            WorkerRunState.FAILED -> {
                pruneActiveKey(info)
                _state.value = _state.value.copy(
                    transcriptionState = TranscriptionStateMapper.map(runState, _state.value.transcriptionState),
                    transcriptionError = info.outputData.getString(TranscriptionWorker.OUT_ERROR_MESSAGE)
                        ?: appContext.getString(R.string.subtitles_transcribe_unsupported),
                )
            }
            WorkerRunState.CANCELLED -> {
                pruneActiveKey(info)
                _state.value = _state.value.copy(
                    transcriptionState = TranscriptionStateMapper.map(runState, _state.value.transcriptionState),
                    transcriptionProgress = 0,
                    transcriptionStage = null,
                )
            }
            WorkerRunState.INFO -> Unit
        }
    }

    /** Releases the dedup slot when the worker reaches any terminal state. */
    private fun pruneActiveKey(info: WorkInfo) {
        val key = info.outputData.getString(TranscriptionWorker.OUT_DEDUP_KEY).orEmpty()
            .ifBlank { TranscriptionDedup.keyFor(_state.value.transcriptionPath.orEmpty()) }
        if (key.isNotBlank()) activeTranscriptionSet.remove(key)
    }

    /**
     * On success: read the written SRT, parse it into [cues], clear stale translated
     * cues, install a synthetic AUTOMATIC subtitle track, and enable the existing
     * preview -> translation -> download/embed chain (no separate download path added).
     */
    private fun onTranscriptionSucceeded(info: WorkInfo) {
        pruneActiveKey(info)
        val srtPath = info.outputData.getString(TranscriptionWorker.OUT_SRT_PATH).orEmpty()
        val text = runCatching { File(srtPath).readText() }.getOrNull()
        if (text.isNullOrBlank()) {
            _state.value = _state.value.copy(
                transcriptionState = TranscriptionState.FAILED,
                transcriptionError = appContext.getString(R.string.subtitles_transcribe_empty_error),
            )
            return
        }
        when (val parsed = parser.parse(SubtitleFormat.SRT, text)) {
            is AppResult.Success -> {
                val cues = parsed.value
                val explicit = SourceLanguages.toApiCode(_state.value.sourceLanguage)
                val detected = explicit ?: parser.detectLanguage(cues)
                val track = SubtitleTrack(
                    trackId = "whisper_${_state.value.transcriptionJobId ?: "job"}",
                    languageCode = detected ?: "und",
                    languageName = detected ?: "auto",
                    kind = SubtitleKind.AUTOMATIC,
                    defaultFormat = SubtitleFormat.SRT,
                )
                val others = _state.value.subtitleTracks.filterNot { it.trackId == track.trackId }
                _state.value = _state.value.copy(
                    transcriptionState = TranscriptionState.SUCCESS,
                    transcriptionCueCount = cues.size,
                    transcriptionProgress = 100,
                    transcriptionStage = null,
                    cues = cues,
                    translatedCues = null,
                    selectedTrack = track,
                    subtitleTracks = others + track,
                    errorMessage = null,
                )
            }
            is AppResult.Error -> _state.value = _state.value.copy(
                transcriptionState = TranscriptionState.FAILED,
                transcriptionError = "Could not read transcription: ${parsed.message}",
            )
        }
    }

    // ---------------------------------------------------------- Step 6: download

    fun download() {
        val s = _state.value
        // C4: standalone SUBTITLE_ONLY export for a transcribed/loaded cue list with no
        // resolved video link: synthesize minimal local metadata instead of erroring out.
        val standalone = s.downloadMode == DownloadMode.SUBTITLE_ONLY && s.cues.isNotEmpty()
        val metadata = s.metadata ?: if (standalone) {
            StandaloneSubtitleSpecBuilder.buildMetadata(
                sourcePath = s.transcriptionPath,
                jobId = s.transcriptionJobId,
            )
        } else null
        if (metadata == null) {
            _state.value = _state.value.copy(errorMessage = "Resolve a video link first.")
            return
        }
        if (!standalone && s.downloadMode != DownloadMode.VIDEO_ONLY && s.selectedTrack == null) {
            _state.value = _state.value.copy(errorMessage = "Select a subtitle track first.")
            return
        }
        if (s.downloadMode != DownloadMode.SUBTITLE_ONLY && s.selectedVideoTrack == null) {
            _state.value = _state.value.copy(errorMessage = "No video stream available for this source.")
            return
        }
        if (s.translateEnabled && !s.providerConfigured) {
            _state.value = _state.value.copy(
                errorMessage = "Translation is enabled but no provider is configured. Disable translation or configure a provider in Settings.",
            )
            return
        }
        viewModelScope.launch(dispatchers.io) {
            _state.value = _state.value.copy(isDownloading = true, errorMessage = null, downloadEnqueued = false)

            // ---- CRITICAL (CONTRACTS §5): serialize subtitle content into DownloadSpec ----
            // Preview cue edits -> shift applied to BOTH original and translated cues BEFORE
            // format serialization -> raw SRT/VTT/ASS text carried on the spec. DownloadWorker
            // consumes these strings directly and does NOT re-fetch via the adapter. When both
            // are null (legacy path, e.g. subtitle-only with no preview loaded), DownloadWorker
            // falls back to adapter.fetchSubtitle.
            val subtitleIncluded = s.downloadMode != DownloadMode.VIDEO_ONLY
            val bundle = SubtitleExportAssembler.assemble(
                cues = if (subtitleIncluded) s.cues else emptyList(),
                translatedCues = if (subtitleIncluded && s.translateEnabled) s.translatedCues else null,
                shiftMs = s.subtitleShiftMs,
                format = s.subtitleFormat,
                parser = parser,
                targetLanguageCode = s.targetLanguage.takeIf { s.translateEnabled },
                cueOverrides = s.cueOverrides,
            )

            val spec = DownloadSpec(
                metadata = metadata,
                mode = s.downloadMode,
                videoTrack = if (s.downloadMode != DownloadMode.SUBTITLE_ONLY) s.selectedVideoTrack else null,
                audioTrack = if (s.downloadMode != DownloadMode.SUBTITLE_ONLY) s.selectedAudioTrack else null,
                subtitleTrack = if (s.downloadMode != DownloadMode.VIDEO_ONLY) s.selectedTrack else null,
                subtitleFormat = s.subtitleFormat,
                translateTo = if (s.translateEnabled) s.targetLanguage else null,
                embedMode = if (s.downloadMode == DownloadMode.SUBTITLE_ONLY) EmbedMode.NONE else s.embedMode,
                embedStyle = s.embedStyle,
                outputFileName = sanitizeFileName(metadata.title),
                // --- CONTRACTS §1 frozen field names (Agent B adds to core:model) ---
                subtitleContentOriginal = bundle.originalText,
                subtitleContentTranslated = bundle.translatedText,
                useTranslatedSubtitle = bundle.useTranslated,
                subtitleShiftMs = bundle.shiftMs,
                saveOriginalSubtitleSidecar = s.saveOriginalSubtitleSidecar,
                translatedLanguageCode = bundle.translatedLanguageCode,
            )
            when (val result = downloadManager.enqueue(spec)) {
                is AppResult.Success -> _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadEnqueued = true,
                )
                is AppResult.Error -> _state.value = _state.value.copy(
                    isDownloading = false,
                    errorMessage = result.message,
                )
            }
        }
    }

    private fun sanitizeFileName(title: String): String =
        title.replace(Regex("""[\\/:*?"<>|\n\r\t]+"""), "_")
            .trim()
            .replace(Regex("\\s+"), "_")
            .take(120)
            .ifBlank { "vidmatex_video" }
}
