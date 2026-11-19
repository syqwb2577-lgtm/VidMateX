package app.vidmatex.core.model

import kotlinx.serialization.Serializable

/** Supported source platforms. UNKNOWN means the link is not handled by any compliant adapter. */
@Serializable
enum class SourceType { YOUTUBE, FACEBOOK, TIKTOK, DIRECT_URL, UNKNOWN }

/** How a subtitle track was produced. Never relabel AUTOMATIC as human-created. */
@Serializable
enum class SubtitleKind { OFFICIAL, CREATOR_PROVIDED, HUMAN, AUTOMATIC, TRANSLATED }

@Serializable
enum class SubtitleFormat { SRT, VTT, ASS }

@Serializable
enum class MediaKind { VIDEO, AUDIO }

/**
 * Download intent. [AUDIO_ONLY] (additive; appended LAST to preserve ordinal/name
 * compatibility) means a standalone audio stream: no video track, no mux. For specs
 * written before this value existed, audio-only is detected structurally:
 * `videoTrack == null && audioTrack != null`.
 */
@Serializable
enum class DownloadMode { VIDEO_ONLY, SUBTITLE_ONLY, VIDEO_AND_SUBTITLE, AUDIO_ONLY }

/** NONE = keep files separate; SOFT = selectable track in container; HARD = burned into frames. */
@Serializable
enum class EmbedMode { NONE, SOFT, HARD }

@Serializable
enum class DownloadState { QUEUED, ACTIVE, PAUSED, COMPLETED, FAILED, CANCELLED }

@Serializable
enum class TranslationProvider { NONE, ML_KIT, LIBRE_TRANSLATE }

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class AppLanguage { SYSTEM, ENGLISH, ARABIC }

/** Metadata resolved for a single source URL. */
@Serializable
data class VideoMetadata(
    val sourceType: SourceType,
    val sourceUrl: String,
    val sourceId: String,
    val title: String,
    val author: String? = null,
    val durationSec: Long? = null,
    val thumbnailUrl: String? = null,
    val description: String? = null,
)

/** One downloadable stream (video or audio) offered by the source. */
@Serializable
data class MediaTrack(
    val trackId: String,
    val kind: MediaKind,
    val container: String,          // mp4, webm, m4a, mp3
    val heightPx: Int? = null,      // null for audio
    val widthPx: Int? = null,
    val fps: Int? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val bitrateBps: Int? = null,
    val sampleRate: Int? = null,
    val estimatedBytes: Long? = null,
    val language: String? = null,
    val resumable: Boolean = false,        // true when server sends Accept-Ranges: bytes
) {
    val qualityLabel: String
        get() = if (kind == MediaKind.VIDEO) "${heightPx ?: "?"}p${fps?.let { if (it > 0) "$it" else "" } ?: ""}" else container.uppercase()
}

/** One subtitle/caption track offered by the source. */
@Serializable
data class SubtitleTrack(
    val trackId: String,
    val languageCode: String,        // e.g. "ar", "en"
    val languageName: String,        // e.g. "Arabic"
    val kind: SubtitleKind,
    val isTranslatable: Boolean = true,
    val defaultFormat: SubtitleFormat = SubtitleFormat.VTT,
) {
    /** User-facing label, e.g. "Arabic — Official". */
    val displayLabel: String get() = "$languageName — ${kind.name.lowercase().replaceFirstChar { it.uppercase() }}"
}

/** One timed subtitle cue. Timestamps in milliseconds. */
@Serializable
data class SubtitleCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val lines: List<String>,
)

/** Styling for hard-burned subtitles (passed into an allowlisted FFmpeg force_style string). */
@Serializable
data class EmbedStyle(
    val fontSize: Int = 24,
    val fontColor: String = "&H00FFFFFF",   // ASS BGR hex
    val outlineColor: String = "&H00000000",
    val outlineWidth: Float = 2f,
    val backgroundColor: String = "&H80000000",
    val bold: Boolean = true,
    val alignment: Int = 2,                 // ASS alignment: 2 = bottom-center
    val marginV: Int = 40,
    val fontName: String = "Sans",          // must exist on device; Arabic needs a font with Arabic glyphs
)

/** Full user intent for one download+subtitle job. */
@Serializable
data class DownloadSpec(
    val metadata: VideoMetadata,
    val mode: DownloadMode,
    val videoTrack: MediaTrack? = null,
    val audioTrack: MediaTrack? = null,
    val subtitleTrack: SubtitleTrack? = null,
    val subtitleFormat: SubtitleFormat = SubtitleFormat.SRT,
    val translateTo: String? = null,        // target language code, null = no translation
    val embedMode: EmbedMode = EmbedMode.NONE,
    val embedStyle: EmbedStyle = EmbedStyle(),
    val outputFileName: String,
    // --- Subtitle content + shift (added per CONTRACTS §1; defaults preserve old behavior) ---
    /** Raw subtitle text in [subtitleFormat], fetched at preview time (already shift-applied). Null when no subtitle. */
    val subtitleContentOriginal: String? = null,
    /** Translated subtitle text in [subtitleFormat]. Null when no translation was applied. */
    val subtitleContentTranslated: String? = null,
    /** True when the user chose to download/embed the translated subtitle version. */
    val useTranslatedSubtitle: Boolean = false,
    /** User-applied offset (ms) already applied to cues before serialization/write. */
    val subtitleShiftMs: Long = 0,
    /** When true and a translated subtitle is embedded, also write the original as a sidecar file. */
    val saveOriginalSubtitleSidecar: Boolean = true,
    /** Target language code (ISO-639-1) when translation is applied; null otherwise. */
    val translatedLanguageCode: String? = null,
    /**
     * Explicit audio-only marker (additive, default `false`; mirrors [DownloadMode.AUDIO_ONLY]).
     * Structural equivalence: `videoTrack == null && audioTrack != null`. Older serialized
     * specs omit this field and decode as `false`; the worker keeps the structural fallback
     * for such specs, so the flag is an optimization/contract marker, not a requirement.
     */
    val audioOnly: Boolean = false,
)

/** Persisted download record (Room entity mirrors this in core:database). */
@Serializable
data class DownloadItem(
    val id: String,
    val sourceUrl: String,
    val sourceType: SourceType,
    val title: String,
    val thumbnailUrl: String? = null,
    val qualityLabel: String? = null,
    val state: DownloadState = DownloadState.QUEUED,
    val progressPercent: Int = 0,
    val speedBps: Long = 0,
    val etaSec: Long? = null,
    val totalBytes: Long? = null,
    val downloadedBytes: Long = 0,
    val filePath: String? = null,
    val subtitlePath: String? = null,
    val errorMessage: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
)

/** User settings persisted via DataStore. */
@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val downloadDirUri: String? = null,
    val defaultQualityHeight: Int? = null,   // null = ask each time
    val defaultSubtitleLanguage: String? = null,
    val translationProvider: TranslationProvider = TranslationProvider.NONE,
    val libreTranslateBaseUrl: String = "",
    val backendBaseUrl: String = "",          // optional self-hosted yt-dlp resolver endpoint
    /** Optional shared-secret for the resolver backend (sent as X-API-Key header). Empty = auth disabled. Never hardcoded. */
    val resolverApiKey: String = "",
    val whisperEndpoint: String = "",         // optional self-hosted Whisper/OpenAI-compatible STT endpoint
    val whisperApiKey: String = "",           // user-provided, stored locally only; never hardcoded
    val requireWifi: Boolean = false,
    val defaultEmbedMode: EmbedMode = EmbedMode.NONE,
    /**
     * Auto-transcribe downloaded media via the configured Whisper endpoint. Non-secret
     * boolean (persisted as a plain DataStore preference). Default false; when true, the
     * subtitles/processing layer kicks off a transcription job after each download
     * completes. Requires a valid Whisper endpoint (see TranscriptionReadiness).
     */
    val autoTranscribe: Boolean = false,
)

/**
 * Non-secret reference to an encrypted secret entry. Persisted records (download
 * specs, history rows, API config references) carry ONLY this alias — never the key
 * material itself. The plaintext key lives in the Keystore-encrypted secrets store
 * (`feature/settings/security/SecretStore.kt`) and is resolved at call time.
 */
@Serializable
data class SecretKeyRef(
    /** Stable alias into the encrypted secrets store, e.g. "resolver_api_key". */
    val alias: String,
    /** False when no encrypted entry exists for the alias (caller prompts for setup). */
    val present: Boolean = false,
)
