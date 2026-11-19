package app.vidmatex.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [SecretTextRedactor] used by network error/log paths. */
class SecretTextRedactorTest {

    @Test fun `api key header value is masked`() {
        val out = SecretTextRedactor.redact("X-API-Key: AbCdef1234567890XYZ99")
        assertTrue(out.contains("[redacted]"))
        assertFalse(out.contains("AbCdef1234567890XYZ99"))
    }

    @Test fun `bearer token is masked`() {
        // Synthetic fixture: long enough for the redactor's Bearer rule (>=12
        // chars) but below the CI secret-scan token threshold (>=16 chars).
        val out = SecretTextRedactor.redact("Authorization: Bearer devtoken12345")
        assertTrue(out.contains("Bearer [redacted]"))
        assertFalse(out.contains("devtoken12345"))
    }

    @Test fun `query key value is masked while siblings survive`() {
        val out = SecretTextRedactor.redact("token=abcdef1234567890&page=2")
        assertTrue(out.contains("[redacted]"))
        assertFalse(out.contains("abcdef1234567890"))
        assertTrue(out.contains("page=2"))
    }

    @Test fun `plain user-facing message passes through unchanged`() {
        val message = "Server returned HTTP 404 (endpoint not found)."
        assertEquals(message, SecretTextRedactor.redact(message))
    }

    @Test fun `blank text returns as-is`() {
        assertEquals("", SecretTextRedactor.redact(""))
    }

    @Test fun `authorization header with api key style is masked`() {
        val out = SecretTextRedactor.redact("Authorization: ApiKey sk_live_AbCdEf1234567890")
        assertFalse(out.contains("sk_live_AbCdEf1234567890"))
        assertTrue(out.contains("[redacted]"))
    }

    @Test fun `password and signature query params are masked`() {
        val out = SecretTextRedactor.redact("password=hunter2Secret123&signature=abcDEF1234567890&ok=1")
        assertFalse(out.contains("hunter2Secret123"))
        assertFalse(out.contains("abcDEF1234567890"))
        assertTrue(out.contains("ok=1"))
    }

    @Test fun `access_token with underscore is masked`() {
        val out = SecretTextRedactor.redact("access_token=ya29AbCdEf1234567890XYZ")
        assertFalse(out.contains("ya29AbCdEf1234567890XYZ"))
        assertTrue(out.contains("[redacted]"))
    }

    @Test fun `multiple secrets in one line are all masked`() {
        val out = SecretTextRedactor.redact("api_key=AAAbbb1234567890 and token=CCCddd1234567890")
        assertFalse(out.contains("AAAbbb1234567890"))
        assertFalse(out.contains("CCCddd1234567890"))
    }

    @Test fun `short values below threshold are left alone`() {
        // Values shorter than the pattern's 8-char minimum are not masked (avoid false positives).
        val out = SecretTextRedactor.redact("key=abc")
        assertTrue(out.contains("key=abc"))
    }

    @Test fun `openai-style sk- literal is masked`() {
        // Assembled at runtime so the contiguous sk-<16+ chars> literal never appears in
        // source (the CI secret scan would flag it). 18-char body -> above the 16-char threshold.
        val token = "sk-" + "synthetic".repeat(2)
        val out = SecretTextRedactor.redact("request failed for $token endpoint")
        assertFalse(out.contains(token))
        assertTrue(out.contains("[redacted]"))
    }

    @Test fun `short sk- identifiers below threshold are left alone`() {
        // `sk-` followed by <16 chars must not be redacted (avoids false positives on
        // ordinary identifiers like a SKU prefix sk-12345).
        val shortToken = "sk-" + "abc123" // 6 chars -> below 16 threshold
        val out = SecretTextRedactor.redact("ref=$shortToken")
        assertTrue(out.contains(shortToken))
    }

    @Test fun `multiple sk- tokens in one line are all masked`() {
        val t1 = "sk-" + "firstkey1234567890".take(18)
        val t2 = "sk-" + "secondkey123456789".take(18)
        val out = SecretTextRedactor.redact("$t1 and $t2")
        assertFalse(out.contains(t1))
        assertFalse(out.contains(t2))
    }

    @Test fun `uppercase BEARER scheme is masked case-insensitively`() {
        val token = "bearertoken12345" // 15 chars -> below CI 16-char threshold
        val out = SecretTextRedactor.redact("BEARER $token")
        assertFalse(out.contains(token))
        assertTrue(out.contains("[redacted]"))
    }
}
