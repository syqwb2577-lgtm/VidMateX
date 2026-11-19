package app.vidmatex.feature.settings

import app.vidmatex.core.model.AppSettings
import app.vidmatex.core.model.EmbedMode
import app.vidmatex.core.model.ThemeMode
import app.vidmatex.core.model.TranslationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [SettingsMapper]: non-secret DataStore snapshot -> AppSettings,
 * enum fallbacks, numeric quality parsing, and secret pass-through. Encryption is
 * exercised separately at the Keystore boundary; these tests only verify plaintext
 * secrets reach the model unchanged.
 */
class SettingsMapperTest {

    @Test fun `empty snapshot with empty secrets equals default AppSettings`() {
        val mapped = SettingsMapper.toAppSettings(SettingsSnapshot(), resolverApiKey = "", whisperApiKey = "")
        assertEquals(AppSettings(), mapped)
        assertEquals("", mapped.resolverApiKey)
        assertEquals("", mapped.whisperApiKey)
    }

    @Test fun `decrypted secrets pass through unchanged`() {
        val mapped = SettingsMapper.toAppSettings(
            SettingsSnapshot(),
            resolverApiKey = "resolver-shared-secret",
            whisperApiKey = "whisper-shared-secret",
        )
        assertEquals("resolver-shared-secret", mapped.resolverApiKey)
        assertEquals("whisper-shared-secret", mapped.whisperApiKey)
    }

    @Test fun `known enum names map to values`() {
        val mapped = SettingsMapper.toAppSettings(
            SettingsSnapshot(
                themeMode = "DARK",
                defaultEmbedMode = "SOFT",
                translationProvider = "ML_KIT",
            ),
            "", "",
        )
        assertEquals(ThemeMode.DARK, mapped.themeMode)
        assertEquals(EmbedMode.SOFT, mapped.defaultEmbedMode)
        assertEquals(TranslationProvider.ML_KIT, mapped.translationProvider)
    }

    @Test fun `unknown enum names fall back to defaults`() {
        val mapped = SettingsMapper.toAppSettings(
            SettingsSnapshot(themeMode = "NOPE", defaultEmbedMode = "???", translationProvider = "X"),
            "", "",
        )
        assertEquals(ThemeMode.SYSTEM, mapped.themeMode)
        assertEquals(EmbedMode.NONE, mapped.defaultEmbedMode)
        assertEquals(TranslationProvider.NONE, mapped.translationProvider)
    }

    @Test fun `quality height parses numeric and rejects junk or null`() {
        assertEquals(720, SettingsMapper.qualityHeight("720"))
        assertNull(SettingsMapper.qualityHeight("junk"))
        assertNull(SettingsMapper.qualityHeight(null))
    }

    @Test fun `blank non-secret url fields normalize to empty strings`() {
        val mapped = SettingsMapper.toAppSettings(
            SettingsSnapshot(
                backendBaseUrl = "",
                libreTranslateBaseUrl = "",
                whisperEndpoint = "",
            ),
            "", "",
        )
        assertEquals("", mapped.backendBaseUrl)
        assertEquals("", mapped.libreTranslateBaseUrl)
        assertEquals("", mapped.whisperEndpoint)
    }

    @Test fun `blank download dir uri becomes null and non-blank is kept`() {
        assertNull(SettingsMapper.toAppSettings(SettingsSnapshot(downloadDirUri = ""), "", "").downloadDirUri)
        assertEquals(
            "content://tree/123",
            SettingsMapper.toAppSettings(SettingsSnapshot(downloadDirUri = "content://tree/123"), "", "").downloadDirUri,
        )
    }

    @Test fun `requireWifi flag passes through`() {
        assertEquals(true, SettingsMapper.toAppSettings(SettingsSnapshot(requireWifi = true), "", "").requireWifi)
        assertEquals(false, SettingsMapper.toAppSettings(SettingsSnapshot(), "", "").requireWifi)
    }

    @Test fun `fully populated non-secret snapshot maps field-for-field`() {
        val mapped = SettingsMapper.toAppSettings(
            SettingsSnapshot(
                themeMode = "LIGHT",
                appLanguage = "ARABIC",
                downloadDirUri = "content://tree/abcd",
                defaultQualityHeight = "1080",
                defaultSubtitleLanguage = "ar",
                translationProvider = "LIBRE_TRANSLATE",
                libreTranslateBaseUrl = "https://lt.example.com/",
                backendBaseUrl = "https://resolver.example.com/",
                whisperEndpoint = "https://stt.example.com/v1",
                requireWifi = true,
                defaultEmbedMode = "HARD",
            ),
            resolverApiKey = "rk",
            whisperApiKey = "wk",
        )
        assertEquals(app.vidmatex.core.model.ThemeMode.LIGHT, mapped.themeMode)
        assertEquals(app.vidmatex.core.model.AppLanguage.ARABIC, mapped.appLanguage)
        assertEquals("content://tree/abcd", mapped.downloadDirUri)
        assertEquals(1080, mapped.defaultQualityHeight)
        assertEquals("ar", mapped.defaultSubtitleLanguage)
        assertEquals(app.vidmatex.core.model.TranslationProvider.LIBRE_TRANSLATE, mapped.translationProvider)
        assertEquals("https://lt.example.com/", mapped.libreTranslateBaseUrl)
        assertEquals("https://resolver.example.com/", mapped.backendBaseUrl)
        assertEquals("https://stt.example.com/v1", mapped.whisperEndpoint)
        assertEquals(true, mapped.requireWifi)
        assertEquals(app.vidmatex.core.model.EmbedMode.HARD, mapped.defaultEmbedMode)
        assertEquals("rk", mapped.resolverApiKey)
        assertEquals("wk", mapped.whisperApiKey)
    }

    @Test fun `partial snapshot falls back to defaults for untouched fields`() {
        val mapped = SettingsMapper.toAppSettings(
            SettingsSnapshot(themeMode = "DARK"),
            resolverApiKey = "",
            whisperApiKey = "",
        )
        assertEquals(app.vidmatex.core.model.ThemeMode.DARK, mapped.themeMode)
        assertEquals(app.vidmatex.core.model.AppLanguage.SYSTEM, mapped.appLanguage)
        assertEquals("", mapped.backendBaseUrl)
        assertEquals("", mapped.whisperEndpoint)
        assertEquals(false, mapped.requireWifi)
        assertEquals(app.vidmatex.core.model.EmbedMode.NONE, mapped.defaultEmbedMode)
    }

    @Test fun `mapping does not validate endpoint strings, validation lives in readiness checker`() {
        // A non-https endpoint must still map verbatim so the UI/VM can reject it through
        // WhisperProbeUrlValidator / BackendUrlValidator rather than silently dropping data.
        val mapped = SettingsMapper.toAppSettings(
            SettingsSnapshot(
                backendBaseUrl = "http://insecure.example.com/",
                whisperEndpoint = "not-a-url",
            ),
            "", "",
        )
        assertEquals("http://insecure.example.com/", mapped.backendBaseUrl)
        assertEquals("not-a-url", mapped.whisperEndpoint)
    }

    @Test fun `junk quality heights resolve to null, numeric values pass through verbatim`() {
        assertNull(SettingsMapper.qualityHeight("abc"))
        assertNull(SettingsMapper.qualityHeight(""))
        assertNull(SettingsMapper.qualityHeight(null))
        assertEquals(-5, SettingsMapper.qualityHeight("-5"))
        assertEquals(720, SettingsMapper.qualityHeight("720"))
    }

    @Test fun `autoTranscribe defaults to false and passes through`() {
        val default = SettingsMapper.toAppSettings(SettingsSnapshot(), "", "")
        assertFalse(default.autoTranscribe)
        val enabled = SettingsMapper.toAppSettings(SettingsSnapshot(autoTranscribe = true), "", "")
        assertTrue(enabled.autoTranscribe)
        val explicitlyDisabled = SettingsMapper.toAppSettings(SettingsSnapshot(autoTranscribe = false), "", "")
        assertFalse(explicitlyDisabled.autoTranscribe)
    }

    @Test fun `autoTranscribe is preserved alongside all other fields`() {
        val mapped = SettingsMapper.toAppSettings(
            SettingsSnapshot(
                themeMode = "DARK",
                backendBaseUrl = "https://r.example.com/",
                whisperEndpoint = "https://w.example.com/v1",
                autoTranscribe = true,
            ),
            resolverApiKey = "rk",
            whisperApiKey = "wk",
        )
        assertTrue(mapped.autoTranscribe)
        assertEquals(ThemeMode.DARK, mapped.themeMode)
        assertEquals("https://r.example.com/", mapped.backendBaseUrl)
        assertEquals("rk", mapped.resolverApiKey)
        assertEquals("wk", mapped.whisperApiKey)
    }
}
