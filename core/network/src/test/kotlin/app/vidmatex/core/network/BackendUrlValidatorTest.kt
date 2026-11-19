package app.vidmatex.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure tests for [BackendUrlValidator] (okhttp3.HttpUrl has no Android
 * framework dependency here). Covers valid/invalid https base URLs used by
 * RetrofitFactory and the Settings connection test.
 */
class BackendUrlValidatorTest {

    @Test fun `valid https url without trailing slash normalizes with slash`() {
        val url = BackendUrlValidator.toHttpsBaseUrl("https://api.example.com/resolver")
        requireNotNull(url)
        assertEquals("https", url.scheme)
        assertEquals("api.example.com", url.host)
        assertEquals("/resolver/", url.encodedPath)
        assertTrue(BackendUrlValidator.isValidHttpsBaseUrl("https://api.example.com/resolver"))
    }

    @Test fun `valid https url with trailing slash kept`() {
        val url = BackendUrlValidator.toHttpsBaseUrl("https://example.org/")
        requireNotNull(url)
        assertEquals("/", url.encodedPath)
    }

    @Test fun `https with port and path accepted`() {
        assertTrue(BackendUrlValidator.isValidHttpsBaseUrl("https://example.com:8443/backend/"))
    }

    @Test fun `blank and whitespace rejected`() {
        assertNull(BackendUrlValidator.toHttpsBaseUrl(""))
        assertNull(BackendUrlValidator.toHttpsBaseUrl("   "))
        assertFalse(BackendUrlValidator.isValidHttpsBaseUrl("  "))
    }

    @Test fun `plain http rejected`() {
        assertNull(BackendUrlValidator.toHttpsBaseUrl("http://example.com/"))
        assertFalse(BackendUrlValidator.isValidHttpsBaseUrl("http://example.com/"))
    }

    @Test fun `ftp and other schemes rejected`() {
        assertNull(BackendUrlValidator.toHttpsBaseUrl("ftp://example.com/"))
        assertNull(BackendUrlValidator.toHttpsBaseUrl("javascript:alert(1)"))
    }

    @Test fun `malformed strings rejected`() {
        assertNull(BackendUrlValidator.toHttpsBaseUrl("not a url at all"))
        assertNull(BackendUrlValidator.toHttpsBaseUrl("https://"))
        assertNull(BackendUrlValidator.toHttpsBaseUrl("://missing-scheme.example/"))
    }

    @Test fun `host-less https rejected`() {
        assertNull(BackendUrlValidator.toHttpsBaseUrl("https:///path-only"))
    }

    @Test fun `userinfo-containing url still parses but host check passes - validator only enforces https`() {
        // Credentials in URL are discouraged elsewhere (UrlValidator); this test pins
        // the narrow contract here: scheme + host present. No secret validation here.
        val url = BackendUrlValidator.toHttpsBaseUrl("https://user:pass@example.com/")
        requireNotNull(url)
        assertEquals("example.com", url.host)
    }
}
