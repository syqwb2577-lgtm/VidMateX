package app.vidmatex.core.common

object Constants {
    // --- Notification channels / ids ---
    const val NOTIFICATION_CHANNEL_DOWNLOADS = "vidmatex_downloads"
    const val NOTIFICATION_CHANNEL_PROCESSING = "vidmatex_processing"
    const val NOTIFICATION_ID_BASE = 1000

    // --- WorkManager names / tags / input keys ---
    const val WORK_NAME_DOWNLOAD = "vidmatex_download_work"
    const val WORK_TAG_DOWNLOAD = "vidmatex_download"
    const val WORK_TAG_EMBED = "vidmatex_embed"
    const val KEY_DOWNLOAD_ITEM_ID = "download_item_id"
    const val KEY_DOWNLOAD_SPEC = "download_spec_json"

    // --- Preferences ---
    const val PREFS_NAME = "vidmatex_settings"
    /**
     * Second DataStore file holding ONLY encrypted secret tokens (resolver API key,
     * Whisper API key). Non-secret settings live in [PREFS_NAME] as plain preferences.
     * Secrets are never written to [PREFS_NAME] after migration; see SecretStore.kt.
     */
    const val PREFS_SECRETS_NAME = "vidmatex_settings_secrets"

    // --- Secure secret storage (Android Keystore, AES/GCM/NoPadding, 256-bit) ---
    /** Stable Keystore alias for the master secret-encryption key; never rotated casually. */
    const val KEYSTORE_SECRET_ALIAS = "vidmatex_secret_key_v1"
    // Secret-file preference keys (hold Keystore-encrypted Base64 tokens, never plaintext).
    const val PREF_RESOLVER_API_KEY_ENC = "resolver_api_key_enc"
    const val PREF_WHISPER_API_KEY_ENC = "whisper_api_key_enc"
    // Legacy plaintext keys (pre-encryption). Read only for one-time migration; removed on write.
    const val PREF_RESOLVER_API_KEY_LEGACY = "resolver_api_key"
    const val PREF_WHISPER_API_KEY_LEGACY = "whisper_api_key"

    // --- Network: base URLs are EMPTY placeholders by default. No real endpoint or API key ships in the APK. ---
    const val DEFAULT_BACKEND_BASE_URL = ""
    const val DEFAULT_LIBRE_TRANSLATE_BASE_URL = ""
    /** Placeholder API key header value — empty unless the user configures one in Settings. */
    const val API_KEY_HEADER_VALUE = ""

    // --- FFmpeg / media processing defaults ---
    const val FFMPEG_DEFAULT_OUTPUT_CONTAINER = "mp4"
    const val FFMPEG_SUBTITLE_DEFAULT_FORMAT = "srt"

    // --- URL validation ---
    const val MAX_URL_LENGTH = 2048

    /** MIME allowlist for direct-URL video sources. */
    val VIDEO_MIME_PREFIXES = listOf("video/", "application/octet-stream")
    val AUDIO_MIME_PREFIXES = listOf("audio/")

    /** Allowed URL schemes — anything else (javascript, file, data, etc.) is rejected. */
    val ALLOWED_SCHEMES = listOf("https", "http")

    // --- Resume-policy persistence (DataStore JSON map: "itemId:kind" -> resumable) ---
    /**
     * DataStore preference key holding a serialized `Map<String, Boolean>`: keys are
     * `"$itemId::$kind"` (`kind` ∈ `video` | `audio` | `subtitle`), values record whether the
     * prior download accepted HTTP Range (HTTP 206 / `Accept-Ranges: bytes`). Persistence
     * fallback for the process-local resume decision store; companion to the Room
     * `DownloadEntity.resumable` column. Safe default when the key/entry is absent: treat the
     * job as non-resumable, re-probe on next run, and never append onto an unverified partial.
     */
    const val PREF_RESUME_MAP_JSON = "vidmatex_resume_map_json"
}
