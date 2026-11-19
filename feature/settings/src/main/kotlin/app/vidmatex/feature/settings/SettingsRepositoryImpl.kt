package app.vidmatex.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.vidmatex.core.common.Constants
import app.vidmatex.core.common.SettingsRepository
import app.vidmatex.core.model.AppSettings
import app.vidmatex.feature.settings.security.SecretCipher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Main preferences file: non-secret settings as plain preferences.
 * Secrets never live here after migration (see [secretsDataStore]).
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = Constants.PREFS_NAME)

/**
 * Dedicated secrets file: ONLY Keystore-encrypted Base64 tokens for the resolver
 * API key and the Whisper API key. Separate file keeps ciphertext isolated from
 * non-secret preferences; encryption/decryption happens at this boundary so the
 * rest of the app sees plaintext only in memory.
 */
private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(name = Constants.PREFS_SECRETS_NAME)

/** Individual preference keys — one per AppSettings field, no opaque JSON blob. */
private object Keys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val APP_LANGUAGE = stringPreferencesKey("app_language")
    val DOWNLOAD_DIR_URI = stringPreferencesKey("download_dir_uri")
    val DEFAULT_QUALITY_HEIGHT = stringPreferencesKey("default_quality_height") // stored as string, null = ask
    val DEFAULT_SUBTITLE_LANGUAGE = stringPreferencesKey("default_subtitle_language")
    val TRANSLATION_PROVIDER = stringPreferencesKey("translation_provider")
    val LIBRE_TRANSLATE_BASE_URL = stringPreferencesKey("libre_translate_base_url")
    /**
     * Resolver Server URL. [AppSettings.backendBaseUrl] IS the Resolver Server URL
     * setting — there is deliberately no second/duplicate URL field for the resolver.
     */
    val BACKEND_BASE_URL = stringPreferencesKey("backend_base_url")
    val WHISPER_ENDPOINT = stringPreferencesKey("whisper_endpoint")
    val REQUIRE_WIFI = booleanPreferencesKey("require_wifi")
    val DEFAULT_EMBED_MODE = stringPreferencesKey("default_embed_mode")
    val AUTO_TRANSCRIBE = booleanPreferencesKey("auto_transcribe")

    // --- Secrets file: encrypted tokens only (never plaintext) ---
    val RESOLVER_API_KEY_ENC = stringPreferencesKey(Constants.PREF_RESOLVER_API_KEY_ENC)
    val WHISPER_API_KEY_ENC = stringPreferencesKey(Constants.PREF_WHISPER_API_KEY_ENC)

    // --- Legacy plaintext rows (pre-encryption). Read for one-time migration only;
    //     removed on the next write and never re-introduced. ---
    val RESOLVER_API_KEY_LEGACY = stringPreferencesKey(Constants.PREF_RESOLVER_API_KEY_LEGACY)
    val WHISPER_API_KEY_LEGACY = stringPreferencesKey(Constants.PREF_WHISPER_API_KEY_LEGACY)
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secretCipher: SecretCipher,
) : SettingsRepository {

    private val settingsStore: DataStore<Preferences> get() = context.settingsDataStore
    private val secretsStore: DataStore<Preferences> get() = context.secretsDataStore

    override val settings: Flow<AppSettings> =
        combine(settingsStore.data, secretsStore.data) { prefs, secrets ->
            prefs.toAppSettings(secrets, secretCipher)
        }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        val current = settingsStore.data.first().toAppSettings(secretsStore.data.first(), secretCipher)
        val next = transform(current)
        // Non-secret preferences first; encrypted secret tokens second.
        settingsStore.edit { prefs -> prefs.writeNonSecrets(next) }
        secretsStore.edit { secrets -> secrets.writeSecrets(next, secretCipher) }
    }

    override suspend fun current(): AppSettings = settings.first()

    /**
     * Boundary read: decrypts secret tokens, migrates legacy plaintext rows in
     * memory, and delegates field mapping to the pure [SettingsMapper].
     */
    private fun Preferences.toAppSettings(secrets: Preferences, cipher: SecretCipher): AppSettings {
        val snapshot = SettingsSnapshot(
            themeMode = this[Keys.THEME_MODE],
            appLanguage = this[Keys.APP_LANGUAGE],
            downloadDirUri = this[Keys.DOWNLOAD_DIR_URI],
            defaultQualityHeight = this[Keys.DEFAULT_QUALITY_HEIGHT],
            defaultSubtitleLanguage = this[Keys.DEFAULT_SUBTITLE_LANGUAGE],
            translationProvider = this[Keys.TRANSLATION_PROVIDER],
            libreTranslateBaseUrl = this[Keys.LIBRE_TRANSLATE_BASE_URL],
            backendBaseUrl = this[Keys.BACKEND_BASE_URL],
            whisperEndpoint = this[Keys.WHISPER_ENDPOINT],
            requireWifi = this[Keys.REQUIRE_WIFI] ?: false,
            defaultEmbedMode = this[Keys.DEFAULT_EMBED_MODE],
            autoTranscribe = this[Keys.AUTO_TRANSCRIBE] ?: false,
        )
        val resolverKey = cipher.decrypt(secrets[Keys.RESOLVER_API_KEY_ENC].orEmpty())
            .ifBlank { this[Keys.RESOLVER_API_KEY_LEGACY].orEmpty() }
        val whisperKey = cipher.decrypt(secrets[Keys.WHISPER_API_KEY_ENC].orEmpty())
            .ifBlank { this[Keys.WHISPER_API_KEY_LEGACY].orEmpty() }
        return SettingsMapper.toAppSettings(snapshot, resolverKey, whisperKey)
    }

    /** Boundary write for non-secret preferences; drops legacy plaintext secret rows. */
    private fun MutablePreferences.writeNonSecrets(s: AppSettings) {
        this[Keys.THEME_MODE] = s.themeMode.name
        this[Keys.APP_LANGUAGE] = s.appLanguage.name
        this[Keys.DOWNLOAD_DIR_URI] = s.downloadDirUri.orEmpty()
        this[Keys.DEFAULT_QUALITY_HEIGHT] = s.defaultQualityHeight?.toString().orEmpty()
        this[Keys.DEFAULT_SUBTITLE_LANGUAGE] = s.defaultSubtitleLanguage.orEmpty()
        this[Keys.TRANSLATION_PROVIDER] = s.translationProvider.name
        this[Keys.LIBRE_TRANSLATE_BASE_URL] = s.libreTranslateBaseUrl
        this[Keys.BACKEND_BASE_URL] = s.backendBaseUrl
        this[Keys.WHISPER_ENDPOINT] = s.whisperEndpoint
        this[Keys.REQUIRE_WIFI] = s.requireWifi
        this[Keys.DEFAULT_EMBED_MODE] = s.defaultEmbedMode.name
        this[Keys.AUTO_TRANSCRIBE] = s.autoTranscribe
        // Migration: legacy plaintext secret rows must not survive a write.
        remove(Keys.RESOLVER_API_KEY_LEGACY)
        remove(Keys.WHISPER_API_KEY_LEGACY)
    }

    /** Boundary write for secrets: plaintext in memory → encrypted token persisted. Blank = row removed. */
    private fun MutablePreferences.writeSecrets(s: AppSettings, cipher: SecretCipher) {
        if (s.resolverApiKey.isBlank()) remove(Keys.RESOLVER_API_KEY_ENC)
        else this[Keys.RESOLVER_API_KEY_ENC] = cipher.encrypt(s.resolverApiKey)
        if (s.whisperApiKey.isBlank()) remove(Keys.WHISPER_API_KEY_ENC)
        else this[Keys.WHISPER_API_KEY_ENC] = cipher.encrypt(s.whisperApiKey)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
