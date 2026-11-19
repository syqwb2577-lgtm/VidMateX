package app.vidmatex.core.common

import app.vidmatex.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UrlValidator tests. NOTE: [UrlValidator.isValid] delegates the final web-URL shape
 * check to android.util.Patterns, which is unavailable in plain JVM unit tests. The
 * pure logic ([UrlValidator.structuralCheck], [UrlValidator.hasBlockedScheme],
 * [UrlValidator.isAllowedScheme], normalization, canonicalization, source detection,
 * private-host detection) is covered below; full `isValid` coverage requires
 * Robolectric or an instrumented (androidTest) run. Status: source-ready, pending CI.
 */
class UrlValidatorTest {
    // ------------------------------------------------------------ isLikelyPrivateHost
    @Test fun `localhost and local domains are private`() {
        assertTrue(UrlValidator.isLikelyPrivateHost("localhost"))
        assertTrue(UrlValidator.isLikelyPrivateHost("LOCALHOST"))
        assertTrue(UrlValidator.isLikelyPrivateHost("api.localhost"))
        assertTrue(UrlValidator.isLikelyPrivateHost("printer.local"))
        assertTrue(UrlValidator.isLikelyPrivateHost("db.internal"))
        assertTrue(UrlValidator.isLikelyPrivateHost("server.lan"))
        assertTrue(UrlValidator.isLikelyPrivateHost("nas.home"))
    }
    @Test fun `private IPv4 ranges are detected`() {
        assertTrue(UrlValidator.isLikelyPrivateHost("127.0.0.1"))
        assertTrue(UrlValidator.isLikelyPrivateHost("10.0.0.1"))
        assertTrue(UrlValidator.isLikelyPrivateHost("10.255.255.255"))
        assertTrue(UrlValidator.isLikelyPrivateHost("192.168.1.1"))
        assertTrue(UrlValidator.isLikelyPrivateHost("172.16.0.1"))
        assertTrue(UrlValidator.isLikelyPrivateHost("172.31.255.255"))
        assertTrue(UrlValidator.isLikelyPrivateHost("169.254.1.1")) // link-local
        assertTrue(UrlValidator.isLikelyPrivateHost("0.0.0.0"))
        assertTrue(UrlValidator.isLikelyPrivateHost("224.0.0.1")) // multicast
        assertTrue(UrlValidator.isLikelyPrivateHost("255.255.255.255"))
    }
    @Test fun `reserved documentation ranges are private`() {
        assertTrue(UrlValidator.isLikelyPrivateHost("198.51.100.7")) // TEST-NET-2
        assertTrue(UrlValidator.isLikelyPrivateHost("203.0.113.9"))  // TEST-NET-3
        assertTrue(UrlValidator.isLikelyPrivateHost("192.0.2.1"))    // TEST-NET-1
        assertTrue(UrlValidator.isLikelyPrivateHost("100.64.0.1"))    // CGNAT
    }
    @Test fun `public IPv4 addresses are not private`() {
        assertFalse(UrlValidator.isLikelyPrivateHost("8.8.8.8"))
        assertFalse(UrlValidator.isLikelyPrivateHost("1.1.1.1"))
        assertFalse(UrlValidator.isLikelyPrivateHost("172.32.0.1")) // just outside 172.16/12
        assertFalse(UrlValidator.isLikelyPrivateHost("172.15.255.255"))
        assertFalse(UrlValidator.isLikelyPrivateHost("192.169.0.1"))
        assertFalse(UrlValidator.isLikelyPrivateHost("11.0.0.1"))
        assertFalse(UrlValidator.isLikelyPrivateHost("100.63.255.255")) // just below CGNAT
    }
    @Test fun `IPv6 loopback link-local and unique-local are private`() {
        assertTrue(UrlValidator.isLikelyPrivateHost("[::1]"))
        assertTrue(UrlValidator.isLikelyPrivateHost("[::]"))
        assertTrue(UrlValidator.isLikelyPrivateHost("[fe80::1]"))
        assertTrue(UrlValidator.isLikelyPrivateHost("[fc00::1]"))
        assertTrue(UrlValidator.isLikelyPrivateHost("[fd12:3456::1]"))
    }
    @Test fun `empty and malformed hosts are treated as private`() {
        assertTrue(UrlValidator.isLikelyPrivateHost(""))
        assertTrue(UrlValidator.isLikelyPrivateHost("999.999.999.999"))
    }
    @Test fun `normal public hostnames are not private`() {
        assertFalse(UrlValidator.isLikelyPrivateHost("example.com"))
        assertFalse(UrlValidator.isLikelyPrivateHost("cdn.example.org"))
        assertFalse(UrlValidator.isLikelyPrivateHost("youtube.com"))
    }
    // ------------------------------------------------------------ normalize / canonicalize / detectSource
    @Test fun `normalize prepends https when scheme missing`() {
        assertEquals("https://example.com/video.mp4", UrlValidator.normalize("example.com/video.mp4"))
        assertEquals("https://example.com", UrlValidator.normalize("  example.com  "))
    }
    @Test fun `normalize preserves existing scheme`() {
        assertEquals("http://example.com/x", UrlValidator.normalize("http://example.com/x"))
        assertEquals("https://example.com/x", UrlValidator.normalize("https://example.com/x"))
    }
    @Test fun `normalize never rewrites a blocked scheme`() {
        assertEquals("javascript:alert(1)", UrlValidator.normalize("javascript:alert(1)"))
        assertEquals("file:///etc/passwd", UrlValidator.normalize("file:///etc/passwd"))
    }
    @Test fun `canonicalize strips tracking params`() {
        val out = UrlValidator.canonicalize("https://example.com/v?id=1&utm_source=x&fbclid=y")
        assertTrue(out, out.contains("id=1"))
        assertFalse(out, out.contains("utm_source"))
        assertFalse(out, out.contains("fbclid"))
    }
    @Test fun `canonicalize keeps url without query unchanged`() {
        assertEquals("https://example.com/v/123", UrlValidator.canonicalize("https://example.com/v/123"))
    }
    @Test fun `detectSource classifies platforms and direct urls`() {
        assertEquals(SourceType.YOUTUBE, UrlValidator.detectSource("https://youtube.com/watch?v=1"))
        assertEquals(SourceType.YOUTUBE, UrlValidator.detectSource("https://youtu.be/abc"))
        assertEquals(SourceType.FACEBOOK, UrlValidator.detectSource("https://facebook.com/video"))
        assertEquals(SourceType.TIKTOK, UrlValidator.detectSource("https://tiktok.com/@user/v/1"))
        assertEquals(SourceType.DIRECT_URL, UrlValidator.detectSource("https://example.com/video.mp4"))
        assertEquals(SourceType.UNKNOWN, UrlValidator.detectSource("not a url at all"))
    }
    // ------------------------------------------------------------ scheme / host rules (pure structuralCheck)
    @Test fun `isAllowedScheme accepts only http and https`() {
        assertTrue(UrlValidator.isAllowedScheme("https"))
        assertTrue(UrlValidator.isAllowedScheme("HTTP"))
        assertFalse(UrlValidator.isAllowedScheme("ftp"))
        assertFalse(UrlValidator.isAllowedScheme(null))
    }
    @Test fun `hasBlockedScheme catches dangerous schemes with and without slashes`() {
        assertTrue(UrlValidator.hasBlockedScheme("javascript:alert(1)"))
        assertTrue(UrlValidator.hasBlockedScheme("javascript://evil/x"))
        assertTrue(UrlValidator.hasBlockedScheme("file:///etc/passwd"))
        assertTrue(UrlValidator.hasBlockedScheme("data:text/html,<h1>hi</h1>"))
        assertTrue(UrlValidator.hasBlockedScheme("ftp://example.com/f"))
        assertFalse(UrlValidator.hasBlockedScheme("https://example.com"))
        assertFalse(UrlValidator.hasBlockedScheme("example.com/video.mp4"))
    }
    @Test fun `structuralCheck accepts plain http s urls`() {
        assertTrue(UrlValidator.structuralCheck("https://example.com/video.mp4"))
        assertTrue(UrlValidator.structuralCheck("http://example.com/watch?v=1"))
        assertTrue(UrlValidator.structuralCheck("  https://example.com/x  "))
    }
    @Test fun `structuralCheck rejects missing host`() {
        assertFalse(UrlValidator.structuralCheck("https://"))
        assertFalse(UrlValidator.structuralCheck("https:///path"))
        assertFalse(UrlValidator.structuralCheck("http://"))
    }
    @Test fun `structuralCheck rejects non http s schemes`() {
        assertFalse(UrlValidator.structuralCheck("ftp://example.com/file"))
        assertFalse(UrlValidator.structuralCheck("mailto:user@example.com"))
        assertFalse(UrlValidator.structuralCheck("custom://example.com/x"))
    }
    @Test fun `structuralCheck rejects dangerous schemes`() {
        assertFalse(UrlValidator.structuralCheck("javascript:alert(1)"))
        assertFalse(UrlValidator.structuralCheck("file:///etc/passwd"))
        assertFalse(UrlValidator.structuralCheck("data:text/html,<h1>hi"))
        assertFalse(UrlValidator.structuralCheck("content://media/external/file"))
    }
    @Test fun `structuralCheck rejects urls with userinfo credentials`() {
        assertFalse(UrlValidator.structuralCheck("https://user:pass@example.com/video"))
        assertFalse(UrlValidator.structuralCheck("http://admin@example.com/"))
    }
    @Test fun `structuralCheck rejects empty and oversized`() {
        assertFalse(UrlValidator.structuralCheck(""))
        assertFalse(UrlValidator.structuralCheck("   "))
        assertFalse(UrlValidator.structuralCheck("h".repeat(Constants.MAX_URL_LENGTH + 10)))
    }
    // ------------------------------------------------------------ isValid early-return paths (before android Patterns)
    @Test fun `isValid rejects empty and oversized`() {
        assertFalse(UrlValidator.isValid(""))
        assertFalse(UrlValidator.isValid(" ".repeat(Constants.MAX_URL_LENGTH + 10)))
    }
    @Test fun `isValid rejects blocked schemes before hitting android Patterns`() {
        assertFalse(UrlValidator.isValid("ftp://example.com/file"))
        assertFalse(UrlValidator.isValid("javascript://x"))
        assertFalse(UrlValidator.isValid("file:///etc/passwd"))
        assertFalse(UrlValidator.isValid("data:text/html,<h1>hi"))
        assertFalse(UrlValidator.isValid("javascript:alert(1)"))
    }

    // ------------------------------------------------------------ hardening: private/metadata literals
    @Test fun `structuralCheck rejects private and metadata IP literals`() {
        assertFalse(UrlValidator.structuralCheck("https://127.0.0.1/x"))
        assertFalse(UrlValidator.structuralCheck("https://10.0.0.5/x"))
        assertFalse(UrlValidator.structuralCheck("https://192.168.1.1/x"))
        assertFalse(UrlValidator.structuralCheck("https://172.16.0.1/x"))
        assertFalse(UrlValidator.structuralCheck("https://169.254.169.254/latest/meta-data"))
        assertFalse(UrlValidator.structuralCheck("https://[fe80::1]/x"))
        assertFalse(UrlValidator.structuralCheck("https://[fc00::1]/x"))
        assertFalse(UrlValidator.structuralCheck("https://[2001:db8::1]/x"))
    }

    @Test fun `structuralCheck rejects control characters in url`() {
        assertFalse(UrlValidator.structuralCheck("https://example.com/a\tb.mp4"))
        assertFalse(UrlValidator.structuralCheck("https://example.com/a\nb.mp4"))
        assertFalse(UrlValidator.structuralCheck("https://example.com/a\u0000b.mp4"))
    }

    @Test fun `structuralCheck rejects malformed host shapes`() {
        assertFalse(UrlValidator.structuralCheck("https://-bad-.com/x"))
        assertFalse(UrlValidator.structuralCheck("https://example..com/x"))
        assertFalse(UrlValidator.structuralCheck("https://exa mple.com/x"))
    }

    @Test fun `structuralCheck rejects userinfo without password too`() {
        assertFalse(UrlValidator.structuralCheck("https://operator@example.com/x"))
    }

    // ------------------------------------------------------------ strict remote gate
    @Test fun `isRemoteFetchAllowed requires https and a public host`() {
        assertTrue(UrlValidator.isRemoteFetchAllowed("https://example.com/v.mp4"))
        assertFalse(UrlValidator.isRemoteFetchAllowed("http://example.com/v.mp4"))
        assertFalse(UrlValidator.isRemoteFetchAllowed("https://127.0.0.1/v.mp4"))
        assertFalse(UrlValidator.isRemoteFetchAllowed("https://user:pass@example.com/v.mp4"))
        assertFalse(UrlValidator.isRemoteFetchAllowed("javascript:alert(1)"))
    }

    // ------------------------------------------------------------ local media uri gate
    @Test fun `local file uri accepted only under allowed roots and without traversal`() {
        val roots = listOf("/data/app/private/files", "/cache")
        assertTrue(UrlValidator.isAllowedLocalMediaUri("file:///data/app/private/files/movie.mp4", roots))
        assertTrue(UrlValidator.isAllowedLocalMediaUri("file:///cache/sub/s.srt", roots))
        assertFalse(UrlValidator.isAllowedLocalMediaUri("file:///etc/passwd", roots))
        assertFalse(UrlValidator.isAllowedLocalMediaUri("file:///data/app/private/files/../secret", roots))
        assertFalse(UrlValidator.isAllowedLocalMediaUri("https://example.com/x", roots))
    }

    @Test fun `content uri accepted only for explicitly allowed authorities`() {
        val authorities = setOf("app.vidmatex.provider")
        assertTrue(UrlValidator.isAllowedLocalMediaUri("content://app.vidmatex.provider/media/1", emptyList(), authorities))
        assertFalse(UrlValidator.isAllowedLocalMediaUri("content://media/external/1", emptyList(), authorities))
        assertFalse(UrlValidator.isAllowedLocalMediaUri("content://attacker.example/x", emptyList(), authorities))
    }

    // ------------------------------------------------------------ log redaction
    @Test fun `redactForLog removes userinfo and sensitive query values`() {
        val out = UrlValidator.redactForLog("https://user:pass@cdn.example.com/v.mp4?api_key=TOKEN123XYZ&v=1")
        assertFalse(out.contains("pass"))
        assertFalse(out.contains("TOKEN123XYZ"))
        assertTrue(out.contains("api_key=[redacted]"))
        assertTrue(out.contains("v=1"))
    }

    @Test fun `redactForLog leaves plain public urls intact`() {
        assertEquals("https://example.com/v?id=1", UrlValidator.redactForLog("https://example.com/v?id=1"))
    }

    @Test fun `redactForLog with unparseable input falls back to SecretTextRedactor`() {
        val out = UrlValidator.redactForLog("not a url but token=AbCdef1234567890XYZ")
        assertFalse(out.contains("AbCdef1234567890XYZ"))
    }

    @Test fun `isRemoteFetchAllowed rejects blank and malformed`() {
        assertFalse(UrlValidator.isRemoteFetchAllowed(""))
        assertFalse(UrlValidator.isRemoteFetchAllowed("   "))
        assertFalse(UrlValidator.isRemoteFetchAllowed("ftp://example.com/f"))
    }

    @Test fun `hasUserInfo detects credentials in authority`() {
        assertTrue(UrlValidator.hasUserInfo("https://user:pass@example.com/x"))
        assertTrue(UrlValidator.hasUserInfo("https://user@example.com/x"))
        assertFalse(UrlValidator.hasUserInfo("https://example.com/x"))
    }

    @Test fun `containsControlChars detects tab newline nul del`() {
        assertTrue(UrlValidator.containsControlChars("a\tb"))
        assertTrue(UrlValidator.containsControlChars("a\nb"))
        assertTrue(UrlValidator.containsControlChars("a\u0000b"))
        assertTrue(UrlValidator.containsControlChars("a\u007Fb"))
        assertFalse(UrlValidator.containsControlChars("plain text"))
    }

    @Test fun `isAllowedLocalMediaUri rejects control chars and empty roots`() {
        assertFalse(UrlValidator.isAllowedLocalMediaUri("file:///etc/pass\u0000wd", listOf("/etc")))
        assertFalse(UrlValidator.isAllowedLocalMediaUri("file:///etc/passwd", emptyList()))
        assertFalse(UrlValidator.isAllowedLocalMediaUri("content://x/y", emptyList(), emptySet()))
    }

    @Test fun `isRemoteFetchAllowed is case-insensitive on the scheme`() {
        assertTrue(UrlValidator.isRemoteFetchAllowed("HTTPS://example.com/v.mp4"))
        assertTrue(UrlValidator.isRemoteFetchAllowed("Https://example.com/v.mp4"))
        // Uppercase http must still be rejected (strict remote gate is https-only).
        assertFalse(UrlValidator.isRemoteFetchAllowed("HTTP://example.com/v.mp4"))
    }

    @Test fun `isRemoteFetchAllowed rejects host-less and triple-slash urls`() {
        assertFalse(UrlValidator.isRemoteFetchAllowed("https:///path"))
        assertFalse(UrlValidator.isRemoteFetchAllowed("https://"))
    }
}
