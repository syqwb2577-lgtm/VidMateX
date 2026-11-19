package app.vidmatex.feature.settings.security
// =====================================================================================
// DEVICE-GAP MARKER (written, compile-ready, NOT executed in this environment)
// -------------------------------------------------------------------------------------
// No Android emulator/device exists in this sandbox. These tests are source-ready and
// compile-correct; run on a device/API 24–36 emulator with:
//   ./gradlew :feature:settings:connectedDebugAndroidTest
//
// Covers task item (C): Keystore AES/GCM secret store round-trip on a real device.
// Verifies encrypt→decrypt round-trips (works in both keystore-aes-gcm and the
// plain-fallback degradation path), blank values stay blank, unknown aliases are
// rejected, and describe() never embeds the plaintext.
// =====================================================================================
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretStoreInstrumentedTest {

    @Test
    fun cipher_backendLabel_isOneOfKnownValues() {
        val cipher = AndroidKeystoreSecretCipher()
        assertTrue(
            "backendLabel must be keystore-aes-gcm or plain-fallback (got ${cipher.backendLabel})",
            cipher.backendLabel in setOf("keystore-aes-gcm", "plain-fallback"),
        )
    }

    @Test
    fun encryptDecrypt_roundTripsArbitrarySecret() {
        val cipher = AndroidKeystoreSecretCipher()
        val plain = "sk_live_synthetic-test-secret-1234567890"
        val token = cipher.encrypt(plain)
        val back = cipher.decrypt(token)
        assertEquals("encrypt→decrypt must return the original plaintext", plain, back)
    }

    @Test
    fun blankValues_stayBlank_andNeverThrow() {
        val cipher = AndroidKeystoreSecretCipher()
        assertEquals("", cipher.encrypt(""))
        assertEquals("", cipher.decrypt(""))
    }

    @Test
    fun malformedToken_decryptsToEmptyWithoutThrowing() {
        val cipher = AndroidKeystoreSecretCipher()
        // No "v1:" prefix → treated as legacy/plain; a garbage prefixed token → "".
        assertEquals("", cipher.decrypt("v1:not-valid-base64!!!"))
    }

    @Test
    fun secretStore_knownAliasRoundTrip_andDescribeNeverLeaksValue() {
        val store = SecretStore(AndroidKeystoreSecretCipher())
        val alias = SecretAliases.WHISPER_API_KEY
        val plain = "whisper-synthetic-key-abcdef0123456789"
        val token = store.add(alias, plain)
        assertTrue("present() must be true for a non-blank token", store.present(token))
        val back = store.decrypt(alias, token)
        assertEquals(plain, back)
        val description = store.describe(alias, token)
        assertFalse("describe() must never embed the plaintext", description.contains(plain))
        assertTrue("describe() must include the alias", description.contains(alias))
    }

    @Test
    fun secretStore_unknownAlias_isRejected() {
        val store = SecretStore(AndroidKeystoreSecretCipher())
        var threw = false
        try {
            store.add("not-a-known-alias", "value")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("SecretStore.add must reject unknown aliases", threw)
    }

    @Test
    fun secretStore_clear_returnsBlankToken() {
        val store = SecretStore(AndroidKeystoreSecretCipher())
        val cleared = store.clear()
        assertTrue("clear() must return a blank token", cleared.isBlank())
        assertFalse("present(blank) must be false", store.present(cleared))
        assertFalse("present(null) must be false", store.present(null))
    }
}
