package app.vidmatex.feature.settings.security

/**
 * Crypto-independent token-format helpers for the secure secret store.
 *
 * This class deliberately has NO Android framework imports so its pure logic
 * (prefix tagging, malformed-token rejection, log redaction) can be unit-tested
 * on the plain JVM. The Android Keystore cipher (SecretStore.kt) supplies the
 * actual Base64 encoder/decoder functions; tests supply JVM equivalents.
 *
 * Token format:  `"v1:" + Base64( ivLengthByte || iv || GCM-ciphertext-UTF8 )`
 * A token WITHOUT the prefix [TOKEN_PREFIX] is treated as legacy/plaintext data
 * and never as ciphertext, so a corrupted or legacy row cannot crash decryption.
 */
class SecretCodecs(
    private val encode: (ByteArray) -> String,
    private val decode: (String) -> ByteArray?,
) {
    /** Wraps ciphertext bytes into a persisted token. Empty input yields an empty token. */
    fun wrap(raw: ByteArray): String {
        if (raw.isEmpty()) return ""
        return TOKEN_PREFIX + encode(raw)
    }

    /**
     * Extracts ciphertext bytes from a persisted token.
     * Returns null for: blank input, legacy/plain values (no prefix), or a
     * payload that fails Base64 decoding (malformed/corrupted token).
     */
    fun unwrap(stored: String): ByteArray? {
        if (stored.isEmpty()) return null
        if (!stored.startsWith(TOKEN_PREFIX)) return null
        val payload = stored.substring(TOKEN_PREFIX.length)
        if (payload.isEmpty()) return null
        return runCatching { decode(payload) }.getOrNull()
    }

    /** True when [stored] is a Keystore-encrypted token (prefix present). */
    fun isEncryptedToken(stored: String): Boolean = stored.startsWith(TOKEN_PREFIX)

    /**
     * Safe display form for a secret: length only, never the value. Use whenever a
     * secret must be referenced in a log, error message, or debug state.
     */
    fun redact(value: String): String =
        if (value.isEmpty()) "(empty)" else "****(${value.length} chars)"

    /**
     * Structural sanity check for a decrypted packed token body:
     * `[ivLength:1][iv][ciphertext+GCM tag]`. The Keystore cipher authenticates the GCM
     * tag (device-only); this check rejects obviously truncated/corrupt bytes before they
     * reach the cipher, so a tampered row degrades to "" instead of throwing.
     *
     * GCM standard tag length is 12 bytes; IVs are 12–16 bytes for AES/GCM.
     */
    fun looksPacked(raw: ByteArray): Boolean {
        if (raw.size < 1 + GCM_IV_MIN_BYTES + GCM_TAG_BYTES) return false
        val ivLength = raw[0].toInt() and 0xFF
        if (ivLength !in GCM_IV_MIN_BYTES..GCM_IV_MAX_BYTES) return false
        // At least IV bytes + a full tag + one ciphertext byte.
        return raw.size >= 1 + ivLength + GCM_TAG_BYTES + 1
    }

    /**
     * True when [token] carries the encryption prefix AND its payload decodes to a
     * structurally plausible packed body. Returns false for legacy/plain values, blank
     * payloads, undecodable Base64, and truncated bodies.
     */
    fun wellFormedToken(token: String): Boolean {
        if (!isEncryptedToken(token)) return false
        val raw = unwrap(token) ?: return false
        return looksPacked(raw)
    }

    companion object {
        const val TOKEN_PREFIX = "v1:"
        private const val GCM_TAG_BYTES = 12
        private const val GCM_IV_MIN_BYTES = 12
        private const val GCM_IV_MAX_BYTES = 16
    }
}
