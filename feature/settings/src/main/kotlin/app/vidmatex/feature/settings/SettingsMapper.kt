package app.vidmatex.feature.settings

import app.vidmatex.core.model.AppLanguage
import app.vidmatex.core.model.AppSettings
import app.vidmatex.core.model.EmbedMode
import app.vidmatex.core.model.ThemeMode
import app.vidmatex.core.model.TranslationProvider

/**
 * Plain (Android-framework-free) projection of the non-secret DataStore row.
 * Secrets are NOT part of this snapshot: they arrive decrypted from the
 * [app.vidmatex.feature.settings.security.SecretCipher] as separate parameters.
 */
internal data class SettingsSnapshot(
    val themeMode: String? = null,
    val appLanguage: String? = null,
    val downloadDirUri: String? = null,
    val defaultQualityHeight: String? = null,
    val defaultSubtitleLanguage: String? = null,
    val translationProvider: String? = null,
    val libreTranslateBaseUrl: String? = null,
    val backendBaseUrl: String? = null,
    val whisperEndpoint: String? = null,
    val requireWifi: Boolean = false,
    val defaultEmbedMode: String? = null,
    val autoTranscribe: Boolean = false,
)

/**
 * Pure mapping logic shared by the repository and covered by JVM unit tests.
 * No DataStore, Android, or crypto types appear here.
 */
internal object SettingsMapper {

    fun toAppSettings(
        snapshot: SettingsSnapshot,
        resolverApiKey: String,
        whisperApiKey: String,
    ): AppSettings = AppSettings(
        themeMode = enumFrom(snapshot.themeMode, ThemeMode.SYSTEM),
        appLanguage = enumFrom(snapshot.appLanguage, AppLanguage.SYSTEM),
        downloadDirUri = snapshot.downloadDirUri?.takeIf { it.isNotBlank() },
        defaultQualityHeight = qualityHeight(snapshot.defaultQualityHeight),
        defaultSubtitleLanguage = snapshot.defaultSubtitleLanguage?.takeIf { it.isNotBlank() },
        translationProvider = enumFrom(snapshot.translationProvider, TranslationProvider.NONE),
        libreTranslateBaseUrl = snapshot.libreTranslateBaseUrl.orEmpty(),
        // backendBaseUrl = the Resolver Server URL; no duplicate resolver URL field exists.
        backendBaseUrl = snapshot.backendBaseUrl.orEmpty(),
        resolverApiKey = resolverApiKey,
        whisperEndpoint = snapshot.whisperEndpoint.orEmpty(),
        whisperApiKey = whisperApiKey,
        requireWifi = snapshot.requireWifi,
        defaultEmbedMode = enumFrom(snapshot.defaultEmbedMode, EmbedMode.NONE),
        autoTranscribe = snapshot.autoTranscribe,
    )

    /** Persisted quality height is a string; null/non-numeric resolves to "ask each time". */
    fun qualityHeight(raw: String?): Int? = raw?.toIntOrNull()

    internal inline fun <reified T : Enum<T>> enumFrom(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
