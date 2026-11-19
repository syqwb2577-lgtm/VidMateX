package app.vidmatex.feature.settings.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.vidmatex.core.common.Constants
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts/decrypts user secrets (resolver API key, Whisper API key) at the
 * DataStore boundary. The rest of the app only ever sees plaintext in memory.
 *
 * Primary backend: Android Keystore hardware-backed key, AES/GCM/NoPadding,
 * 256-bit key under the stable alias [Constants.KEYSTORE_SECRET_ALIAS]. The key
 * never leaves the Keystore; only GCM ciphertext (Base64) is persisted.
 *
 * Fallback: if the Keystore is unavailable/corrupt on a device, the cipher
 * degrades to a no-op passthrough so Settings never crashes on startup. The
 * fallback stores the value WITHOUT the encrypted-token prefix and is clearly
 * logged nowhere — key material is never printed, even in this path. Encryption
 * resumes automatically if the Keystore becomes available; legacy plain rows are
 * re-encrypted on the next settings write.
 */
interface SecretCipher {
    /** "keystore-aes-gcm" or "plain-fallback" — diagnostic label only, no secret data. */
    val backendLabel: String

    /** Encrypts a UTF-8 secret to a persisted token; blank stays blank. Never logs the value. */
    fun encrypt(plain: String): String

    /** Decrypts a token to plaintext; blank/malformed/undecryptable values yield "" and never throw. */
    fun decrypt(stored: String): String
}

private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128

class AndroidKeystoreSecretCipher : SecretCipher {

    private val codecs = SecretCodecs(
        encode = { Base64.encodeToString(it, Base64.NO_WRAP) },
        decode = { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() },
    )

    private val keyStore: KeyStore?
    override val backendLabel: String

    init {
        val ks: KeyStore? = try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        } catch (t: Throwable) {
            // Keystore absent/corrupt (e.g. rare emulator/provisioning failure):
            // fall back to passthrough rather than crashing startup.
            null
        }
        keyStore = ks
        backendLabel = if (ks != null) "keystore-aes-gcm" else "plain-fallback"
        if (ks != null && !ks.containsAlias(Constants.KEYSTORE_SECRET_ALIAS)) {
            runCatching { generateKey() }
        }
    }

    private val fallbackActive: Boolean get() = keyStore == null

    private fun generateKey() {
        val spec = KeyGenParameterSpec.Builder(
            Constants.KEYSTORE_SECRET_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(spec)
        generator.generateKey()
    }

    private fun secretKey(): SecretKey {
        val ks = keyStore!!
        val existing = (ks.getEntry(Constants.KEYSTORE_SECRET_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing
        generateKey()
        return (ks.getEntry(Constants.KEYSTORE_SECRET_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    override fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        // Fallback path: plain passthrough, no token prefix. Documented degradation;
        // never logs the value. Normal devices take the Keystore path below.
        if (fallbackActive) return plain
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            // Layout: [ivLength:1][iv][cipherText]
            val packed = ByteArray(1 + iv.size + cipherText.size)
            packed[0] = iv.size.toByte()
            System.arraycopy(iv, 0, packed, 1, iv.size)
            System.arraycopy(cipherText, 0, packed, 1 + iv.size, cipherText.size)
            codecs.wrap(packed)
        }.getOrElse { plain } // last-resort: never fail a settings write because of crypto
    }

    override fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (fallbackActive) return stored
        // Legacy plain row (pre-encryption, or fallback-era): return as-is; the
        // repository re-encrypts it on the next write. No prefix = not ciphertext.
        if (!codecs.isEncryptedToken(stored)) return stored
        val packed = codecs.unwrap(stored) ?: return "" // malformed token → empty, never throw
        return runCatching {
            val ivLength = packed[0].toInt() and 0xFF
            val iv = packed.copyOfRange(1, 1 + ivLength)
            val cipherText = packed.copyOfRange(1 + ivLength, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrDefault("")
    }
}

/**
 * Stable secret aliases referenced everywhere by alias only; key material never appears
 * in code, logs, or error text. The DataStore rows themselves are owned by
 * `SettingsRepositoryImpl`; this facade adds/rotates/clears values through [SecretCipher]
 * and exposes presence diagnostics only.
 */
object SecretAliases {
    const val RESOLVER_API_KEY = "resolver_api_key"
    const val WHISPER_API_KEY = "whisper_api_key"
    val ALL: Set<String> = setOf(RESOLVER_API_KEY, WHISPER_API_KEY)

    fun requireKnown(alias: String) {
        require(alias in ALL) { "Unknown secret alias (value never logged): $alias" }
    }
}

/**
 * Add / rotate / clear facade over [SecretCipher].
 *  - [add]     encrypts a new plaintext value for [alias]; blank yields a blank token.
 *  - [rotate]  replaces the token for [alias] with a fresh encryption of [newPlain]; the
 *    old plaintext is never copied into logs or exceptions.
 *  - [clear]   returns the blank token; the repository removes the encrypted DataStore
 *    row when the persisted token is blank (see `writeSecrets`).
 *  - [present] reports presence only; [describe] never embeds the value.
 */
class SecretStore(private val cipher: SecretCipher) {

    fun add(alias: String, plain: String): String {
        SecretAliases.requireKnown(alias)
        return cipher.encrypt(plain)
    }

    fun rotate(alias: String, newPlain: String): String {
        SecretAliases.requireKnown(alias)
        return cipher.encrypt(newPlain)
    }

    fun clear(): String = ""

    fun decrypt(alias: String, token: String): String {
        SecretAliases.requireKnown(alias)
        return cipher.decrypt(token)
    }

    fun present(token: String?): Boolean = !token.isNullOrBlank()

    /** Diagnostic description: alias + presence only, never the plaintext. */
    fun describe(alias: String, token: String?): String =
        "$alias present=${present(token)} backend=${cipher.backendLabel}"
}
