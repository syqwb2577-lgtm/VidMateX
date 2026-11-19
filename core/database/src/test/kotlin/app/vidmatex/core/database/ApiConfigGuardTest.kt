package app.vidmatex.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [ApiConfigGuard]: non-secret alias/URL/field hygiene. */
class ApiConfigGuardTest {

    @Test fun `canonical aliases accepted and odd ones rejected`() {
        assertTrue(ApiConfigGuard.isValidAlias("resolver"))
        assertTrue(ApiConfigGuard.isValidAlias("libre_translate"))
        assertTrue(ApiConfigGuard.isValidAlias("whisper-1"))
        assertFalse(ApiConfigGuard.isValidAlias("Resolver"))
        assertFalse(ApiConfigGuard.isValidAlias("_private"))
        assertFalse(ApiConfigGuard.isValidAlias("has space"))
        assertFalse(ApiConfigGuard.isValidAlias("a".repeat(41)))
    }

    @Test fun `base url must be blank or https without userinfo`() {
        assertTrue(ApiConfigGuard.isSafeBaseUrl(""))
        assertTrue(ApiConfigGuard.isSafeBaseUrl("https://api.example.com/resolver/"))
        assertFalse(ApiConfigGuard.isSafeBaseUrl("http://api.example.com/"))
        assertFalse(ApiConfigGuard.isSafeBaseUrl("https://user:pass@api.example.com/"))
        assertFalse(ApiConfigGuard.isSafeBaseUrl("https://api.example.com/a\tb"))
    }

    @Test fun `mixed-case long token runs look like secrets`() {
        assertTrue(ApiConfigGuard.looksLikeSecret("AbCdef1234567890XyZ99zz00"))
        assertFalse(ApiConfigGuard.looksLikeSecret("short"))
        assertFalse(ApiConfigGuard.looksLikeSecret("plain descriptive sentence without tokens"))
    }

    @Test fun `sanitizeField drops secret-like values and strips control characters`() {
        assertEquals("", ApiConfigGuard.sanitizeField("AbCdef1234567890XyZ99zz00"))
        assertEquals("Resolver production", ApiConfigGuard.sanitizeField("Resolver production"))
        assertEquals("line1line2", ApiConfigGuard.sanitizeField("line1\u0000line2"))
    }

    @Test fun `looksLikeSecret rejects short values and whitespace`() {
        assertFalse(ApiConfigGuard.looksLikeSecret("Ab12345")) // < 16 chars
        assertFalse(ApiConfigGuard.looksLikeSecret("AbCdef1234567890 XyZ99")) // whitespace
        assertFalse(ApiConfigGuard.looksLikeSecret("alllowercase1234567890")) // no uppercase
        assertFalse(ApiConfigGuard.looksLikeSecret("ALLUPPERCASE1234567890")) // no lowercase
        assertFalse(ApiConfigGuard.looksLikeSecret("NoDigitsHereAtAllABCDEF")) // no digits
    }

    @Test fun `sanitizeField enforces max length and trims`() {
        val long = "a".repeat(300)
        val out = ApiConfigGuard.sanitizeField(long, maxLen = 200)
        assertEquals(200, out.length)
        assertEquals("ok", ApiConfigGuard.sanitizeField("  ok  "))
    }

    @Test fun `isSafeBaseUrl rejects control chars and userinfo in any position`() {
        assertFalse(ApiConfigGuard.isSafeBaseUrl("https://api.example.com/\n"))
        assertFalse(ApiConfigGuard.isSafeBaseUrl("https://token@api.example.com/"))
        // Blank is allowed (unconfigured state).
        assertTrue(ApiConfigGuard.isSafeBaseUrl("   "))
    }

    @Test fun `isValidAlias rejects empty and leading non-alphanumeric`() {
        assertFalse(ApiConfigGuard.isValidAlias(""))
        assertFalse(ApiConfigGuard.isValidAlias("-leading"))
        assertFalse(ApiConfigGuard.isValidAlias("123!@#"))
        assertTrue(ApiConfigGuard.isValidAlias("a"))
    }
}
