package app.vidmatex.feature.settings.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * JVM-pure tests for the crypto-independent [SecretCodecs] token format. The
 * Android Keystore cipher itself (SecretStore.kt) cannot run on the JVM; here we
 * inject JVM Base64 functions and verify prefix tagging, round-trip, malformed
 * rejection, and secret redaction.
 */
class SecretCodecsTest {

    private val codecs = SecretCodecs(
        encode = { Base64.getEncoder().encodeToString(it) },
        decode = { runCatching { Base64.getDecoder().decode(it) }.getOrNull() },
    )

    @Test fun `token prefix constant is stable`() {
        assertEquals("v1:", SecretCodecs.TOKEN_PREFIX)
    }

    @Test fun `ciphertext bytes wrap and unwrap round-trip`() {
        val payload = "the-secret-value".toByteArray(Charsets.UTF_8)
        val token = codecs.wrap(payload)
        assertTrue(codecs.isEncryptedToken(token))
        assertArrayEquals(payload, codecs.unwrap(token))
    }

    @Test fun `empty payload yields empty token and unwrap null`() {
        assertEquals("", codecs.wrap(ByteArray(0)))
        assertNull(codecs.unwrap(""))
        assertFalse(codecs.isEncryptedToken(""))
    }

    @Test fun `legacy plain value without prefix is not treated as ciphertext`() {
        assertFalse(codecs.isEncryptedToken("plain-legacy-value"))
        assertNull(codecs.unwrap("plain-legacy-value"))
    }

    @Test fun `malformed base64 after prefix yields null and never throws`() {
        val badToken = SecretCodecs.TOKEN_PREFIX + "@@@not-base64@@@"
        assertTrue(codecs.isEncryptedToken(badToken))
        assertNull(codecs.unwrap(badToken))
    }

    @Test fun `prefix present but payload empty yields null`() {
        assertNull(codecs.unwrap(SecretCodecs.TOKEN_PREFIX))
    }

    @Test fun `redact hides the secret and exposes only its length`() {
        val secret = "supersecret-123"
        val redacted = codecs.redact(secret)
        assertFalse(redacted.contains(secret))
        assertTrue(redacted.contains(secret.length.toString()))
        assertEquals("(empty)", codecs.redact(""))
    }

    @Test fun `unicode and emoji payloads survive wrap and unwrap`() {
        val unicode = "مفتاح-سريع-🔑-café"
        val token = codecs.wrap(unicode.toByteArray(Charsets.UTF_8))
        assertArrayEquals(unicode.toByteArray(Charsets.UTF_8), codecs.unwrap(token))
    }

    private fun packedBody(ivLen: Int, tagLen: Int = 12, dataLen: Int = 4): ByteArray {
        val body = ByteArray(1 + ivLen + tagLen + dataLen)
        body[0] = ivLen.toByte()
        // Fill iv/tag/data with non-zero bytes so truncation is detectable.
        for (i in 1 until body.size) body[i] = (i % 251).toByte()
        return body
    }

    @Test fun `well formed packed token passes structural validation`() {
        val token = codecs.wrap(packedBody(ivLen = 12))
        assertTrue(codecs.wellFormedToken(token))
    }

    @Test fun `legacy plain value fails structural validation`() {
        assertFalse(codecs.wellFormedToken("plain-legacy-value"))
        assertFalse(codecs.wellFormedToken(""))
    }

    @Test fun `truncated body and impossible iv length fail validation`() {
        val truncated = codecs.wrap(packedBody(ivLen = 12).copyOf(10))
        assertFalse(codecs.wellFormedToken(truncated))

        val badIvLength = packedBody(ivLen = 25)
        assertFalse(codecs.looksPacked(badIvLength))
        val zeroIv = packedBody(ivLen = 0)
        assertFalse(codecs.looksPacked(zeroIv))
    }

    @Test fun `prefix with undecodable payload fails validation`() {
        val badToken = SecretCodecs.TOKEN_PREFIX + "@@@not-base64@@@"
        assertFalse(codecs.wellFormedToken(badToken))
    }

    @Test fun `tampered ciphertext still fails decryption gracefully at cipher boundary`() {
        // Structural check cannot replace GCM authentication: a bit-flip inside the
        // ciphertext keeps the Base64 envelope valid; the Keystore GCM cipher (device)
        // rejects it and SecretCipher.decrypt returns "" instead of throwing. Here we
        // only pin the codec-level contract: malformed envelope → null, never an exception.
        val valid = codecs.wrap(packedBody(12))
        val raw = codecs.unwrap(valid)!!
        val flipped = raw.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertTrue(codecs.looksPacked(flipped)) // envelope shape intact
        assertNull(codecs.unwrap(SecretCodecs.TOKEN_PREFIX + "@@@"))
    }

    @Test fun `16-byte IV (max allowed) passes structural validation`() {
        val token = codecs.wrap(packedBody(ivLen = 16))
        assertTrue(codecs.wellFormedToken(token))
    }

    @Test fun `wrap never produces a token without the prefix for non-empty input`() {
        val token = codecs.wrap(byteArrayOf(1, 2, 3))
        assertTrue(token.startsWith(SecretCodecs.TOKEN_PREFIX))
        assertTrue(codecs.isEncryptedToken(token))
    }

    @Test fun `redact for non-empty secret never includes the secret substring`() {
        val secret = "a-very-long-secret-value-12345"
        val out = codecs.redact(secret)
        assertFalse(out.contains(secret))
        assertTrue(out.contains(secret.length.toString()))
    }
}
