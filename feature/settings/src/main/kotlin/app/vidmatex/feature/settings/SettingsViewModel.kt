package app.vidmatex.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vidmatex.core.common.DispatcherProvider
import app.vidmatex.core.common.SettingsRepository
import app.vidmatex.core.model.AppLanguage
import app.vidmatex.core.model.AppSettings
import app.vidmatex.core.model.EmbedMode
import app.vidmatex.core.model.ThemeMode
import app.vidmatex.core.model.TranslationProvider
import app.vidmatex.core.network.BackendException
import app.vidmatex.core.network.BackendUrlValidator
import app.vidmatex.core.network.RetrofitFactory
import app.vidmatex.core.network.toBackendException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * Connection-test result for the self-hosted resolver. UI maps [Error.kind] to
 * localized strings; the API key never appears in any state field.
 */
sealed interface ResolverTestState {
    data object Idle : ResolverTestState
    data object Checking : ResolverTestState
    data class Ok(
        val version: String?,
        val resolvers: List<String>,
        val testedAtMs: Long,
    ) : ResolverTestState

    data class Error(
        val kind: ErrorKind,
        val httpCode: Int? = null,
        val serverMessage: String? = null,
        val testedAtMs: Long,
    ) : ResolverTestState

    /** Nested at the sealed-interface level so call sites can reference
     *  `ResolverTestState.ErrorKind.NOT_CONFIGURED` directly. */
    enum class ErrorKind { NOT_CONFIGURED, INVALID_URL, TIMEOUT, NO_NETWORK, HTTP_ERROR, UNKNOWN }
}

/** Lightweight reachability result for the Whisper endpoint. Key is never sent or echoed. */
sealed interface WhisperTestState {
    data object Idle : WhisperTestState
    data object Checking : WhisperTestState
    data class Ok(val testedAtMs: Long) : WhisperTestState
    data class Error(val kind: ErrorKind, val testedAtMs: Long) : WhisperTestState

    /** Nested at the sealed-interface level so call sites can reference
     *  `WhisperTestState.ErrorKind.NOT_CONFIGURED` directly. */
    enum class ErrorKind { NOT_CONFIGURED, INVALID_URL, UNREACHABLE }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val retrofitFactory: RetrofitFactory,
    private val httpClient: OkHttpClient,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings()
        )

    private val _resolverTest = MutableStateFlow<ResolverTestState>(ResolverTestState.Idle)
    val resolverTest: StateFlow<ResolverTestState> = _resolverTest.asStateFlow()

    private val _whisperTest = MutableStateFlow<WhisperTestState>(WhisperTestState.Idle)
    val whisperTest: StateFlow<WhisperTestState> = _whisperTest.asStateFlow()

    /**
     * Transcription completeness (§10): blocking when the Whisper endpoint is missing or
     * rejected by [WhisperProbeUrlValidator]; the optional key absent state is a warning
     * only. The Subtitles screen can consume this flow to gate its transcribe action.
     */
    val whisperReadiness: StateFlow<TranscriptionReadiness> = settings
        .map { s ->
            TranscriptionReadiness.evaluate(
                endpoint = s.whisperEndpoint,
                keyPresent = s.whisperApiKey.isNotBlank(),
                endpointIsValid = { WhisperProbeUrlValidator.isValidProbeUrl(it) },
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TranscriptionReadiness.defaultNotReady,
        )

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }
    fun setAppLanguage(lang: AppLanguage) = update { it.copy(appLanguage = lang) }
    fun setDownloadDirUri(uri: String?) = update { it.copy(downloadDirUri = uri) }
    fun setDefaultQualityHeight(height: Int?) = update { it.copy(defaultQualityHeight = height) }
    fun setDefaultSubtitleLanguage(code: String?) = update { it.copy(defaultSubtitleLanguage = code) }
    fun setTranslationProvider(provider: TranslationProvider) = update { it.copy(translationProvider = provider) }
    fun setLibreTranslateBaseUrl(url: String) = update { it.copy(libreTranslateBaseUrl = url) }
    /** Resolver Server URL (stored in AppSettings.backendBaseUrl — the single resolver URL field). */
    fun setBackendBaseUrl(url: String) = update { it.copy(backendBaseUrl = url) }
    /** Resolver shared secret; persisted encrypted via the repository boundary. */
    fun setResolverApiKey(key: String) = update { it.copy(resolverApiKey = key) }
    fun setWhisperEndpoint(url: String) = update { it.copy(whisperEndpoint = url) }
    fun setWhisperApiKey(key: String) = update { it.copy(whisperApiKey = key) }

    /**
     * Secret clear flow: writing a blank value removes the encrypted DataStore row at the
     * repository boundary (`writeSecrets` removes blank keys). Never logs the value.
     */
    fun clearResolverApiKey() = update { it.copy(resolverApiKey = "") }
    fun clearWhisperApiKey() = update { it.copy(whisperApiKey = "") }

    /**
     * Rotate flow: replace a stored secret with a new non-blank value; the repository
     * re-encrypts through the Keystore cipher. Blank rotations are ignored (clear flow
     * above removes a key deliberately).
     */
    fun rotateResolverApiKey(newKey: String) {
        val trimmed = newKey.trim()
        if (trimmed.isNotBlank()) update { it.copy(resolverApiKey = trimmed) }
    }

    fun rotateWhisperApiKey(newKey: String) {
        val trimmed = newKey.trim()
        if (trimmed.isNotBlank()) update { it.copy(whisperApiKey = trimmed) }
    }
    fun setRequireWifi(require: Boolean) = update { it.copy(requireWifi = require) }
    fun setDefaultEmbedMode(mode: EmbedMode) = update { it.copy(defaultEmbedMode = mode) }
    /** Auto-transcribe toggle: persisted as a plain (non-secret) boolean preference. */
    fun setAutoTranscribe(enabled: Boolean) = update { it.copy(autoTranscribe = enabled) }

    /** Explicit Save action: flushes the current snapshot and lets the UI show a confirmation. */
    fun saveNow() = update { it }

    /**
     * Tests the resolver backend: validates the https URL, builds an authenticated
     * API (X-API-Key header added by the interceptor), and calls GET /health.
     * Failures are mapped to friendly, recoverable states; the API key is never
     * placed in a state field or message.
     */
    fun testResolverConnection() {
        viewModelScope.launch(dispatchers.io) {
            _resolverTest.value = ResolverTestState.Checking
            val testedAtMs = System.currentTimeMillis()
            val current = repository.current()
            val baseUrl = current.backendBaseUrl.trim()
            if (baseUrl.isBlank()) {
                _resolverTest.value = ResolverTestState.Error(
                    ResolverTestState.ErrorKind.NOT_CONFIGURED, testedAtMs = testedAtMs,
                )
                return@launch
            }
            if (!BackendUrlValidator.isValidHttpsBaseUrl(baseUrl)) {
                _resolverTest.value = ResolverTestState.Error(
                    ResolverTestState.ErrorKind.INVALID_URL, testedAtMs = testedAtMs,
                )
                return@launch
            }
            val api = retrofitFactory.backendApi(baseUrl, current.resolverApiKey.trim())
            if (api == null) {
                _resolverTest.value = ResolverTestState.Error(
                    ResolverTestState.ErrorKind.INVALID_URL, testedAtMs = testedAtMs,
                )
                return@launch
            }
            try {
                val health = api.health()
                _resolverTest.value = ResolverTestState.Ok(
                    version = health.version?.takeIf { it.isNotBlank() },
                    resolvers = health.resolvers,
                    testedAtMs = testedAtMs,
                )
            } catch (e: Throwable) {
                val be = toBackendException(e)
                val kind = when (be) {
                    is BackendException.Timeout -> ResolverTestState.ErrorKind.TIMEOUT
                    is BackendException.NoNetwork -> ResolverTestState.ErrorKind.NO_NETWORK
                    is BackendException.HttpError -> ResolverTestState.ErrorKind.HTTP_ERROR
                    is BackendException.InvalidRequest -> ResolverTestState.ErrorKind.INVALID_URL
                    is BackendException.MalformedBody -> ResolverTestState.ErrorKind.UNKNOWN
                    is BackendException.Unknown -> ResolverTestState.ErrorKind.UNKNOWN
                }
                _resolverTest.value = ResolverTestState.Error(
                    kind = kind,
                    httpCode = (be as? BackendException.HttpError)?.code,
                    serverMessage = (be as? BackendException.HttpError)?.message?.takeIf { it.isNotBlank() },
                    testedAtMs = testedAtMs,
                )
            }
        }
    }

    /**
     * Lightweight Whisper endpoint reachability check: requires a non-blank URL accepted by
     * [WhisperProbeUrlValidator] (HTTPS always; HTTP only for loopback dev hosts), then
     * probes GET {endpoint}/health and falls back to GET {endpoint}/v1/models.
     * No API key is attached to the probe request and no key appears in the state.
     */
    fun testWhisperConnection() {
        viewModelScope.launch(dispatchers.io) {
            _whisperTest.value = WhisperTestState.Checking
            val testedAtMs = System.currentTimeMillis()
            val endpoint = repository.current().whisperEndpoint.trim()
            if (endpoint.isBlank()) {
                _whisperTest.value = WhisperTestState.Error(
                    WhisperTestState.ErrorKind.NOT_CONFIGURED, testedAtMs = testedAtMs,
                )
                return@launch
            }
            // C5: probe scheme rules mirror WhisperEndpointValidator (media/processor):
            // HTTPS accepted for any host; HTTP accepted ONLY for loopback dev hosts
            // (localhost, 127.0.0.1, ::1, 10.0.2.2, *.localhost) so a locally self-hosted
            // Whisper server can be checked during development. Non-loopback HTTP and other
            // schemes are still rejected. The probe never attaches the API key.
            if (!WhisperProbeUrlValidator.isValidProbeUrl(endpoint)) {
                _whisperTest.value = WhisperTestState.Error(
                    WhisperTestState.ErrorKind.INVALID_URL, testedAtMs = testedAtMs,
                )
                return@launch
            }
            val base = endpoint.trimEnd('/')
            var reachable = false
            for (path in listOf("/health", "/v1/models")) {
                val request = Request.Builder().url("$base$path").get().build()
                try {
                    httpClient.newCall(request).execute().use {
                        // An executed response (even 401/404) proves the host answers;
                        // only IO/connectivity failures mean unreachable.
                        reachable = true
                    }
                } catch (_: Exception) {
                    // Try the next candidate path; do not surface exception text.
                }
                if (reachable) break
            }
            _whisperTest.value = if (reachable) {
                WhisperTestState.Ok(testedAtMs)
            } else {
                WhisperTestState.Error(WhisperTestState.ErrorKind.UNREACHABLE, testedAtMs)
            }
        }
    }

    private fun update(block: (AppSettings) -> AppSettings) {
        viewModelScope.launch(dispatchers.io) { repository.update(block) }
    }
}
