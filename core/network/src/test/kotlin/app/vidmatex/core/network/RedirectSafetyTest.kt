package app.vidmatex.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [RedirectSafety]: every redirect hop must be https, public,
 * credential-free; hop cap and loop detection enforced; relative Locations resolved.
 */
class RedirectSafetyTest {

    @Test fun `valid https public target is allowed`() {
        val verdict = RedirectSafety.validate(
            "https://cdn.example.com/f.mp4", "https://app.example/home", 0, emptySet()
        )
        assertTrue(verdict.allowed)
    }

    @Test fun `http downgrade redirect is rejected`() {
        val verdict = RedirectSafety.validate(
            "http://cdn.example.com/f.mp4", "https://app.example/home", 0, emptySet()
        )
        assertFalse(verdict.allowed)
    }

    @Test fun `private loopback metadata and lan redirects are rejected`() {
        assertFalse(RedirectSafety.validate("https://127.0.0.1/internal", "https://app.example/", 0, emptySet()).allowed)
        assertFalse(RedirectSafety.validate("https://192.168.1.2/x", "https://app.example/", 0, emptySet()).allowed)
        assertFalse(RedirectSafety.validate("https://169.254.169.254/latest/meta-data", "https://app.example/", 0, emptySet()).allowed)
        assertFalse(RedirectSafety.validate("https://[fe80::1]/x", "https://app.example/", 0, emptySet()).allowed)
    }

    @Test fun `redirect target with credentials is rejected`() {
        assertFalse(RedirectSafety.validate("https://u:p@cdn.example.com/x", "https://app.example/", 0, emptySet()).allowed)
    }

    @Test fun `malformed scheme redirect is rejected`() {
        assertFalse(RedirectSafety.validate("javascript:alert(1)", "https://app.example/", 0, emptySet()).allowed)
    }

    @Test fun `hop cap is enforced`() {
        val verdict = RedirectSafety.validate(
            "https://cdn.example.com/a", "https://app.example/", RedirectSafety.MAX_REDIRECT_HOPS, emptySet()
        )
        assertFalse(verdict.allowed)
    }

    @Test fun `redirect loop is rejected`() {
        val seen = setOf(RedirectSafety.canonicalForLoop("https://cdn.example.com/a"))
        val verdict = RedirectSafety.validate("https://cdn.example.com/a", "https://cdn.example.com/b", 1, seen)
        assertFalse(verdict.allowed)
    }

    @Test fun `relative location resolves against referer`() {
        assertEquals(
            "https://app.example/video.mp4",
            RedirectSafety.resolveRelative("https://app.example/series/", "../video.mp4")
        )
    }

    @Test fun `redirect status recognition`() {
        assertTrue(RedirectSafety.isRedirectStatus(301))
        assertTrue(RedirectSafety.isRedirectStatus(308))
        assertFalse(RedirectSafety.isRedirectStatus(200))
        assertFalse(RedirectSafety.isRedirectStatus(500))
    }

    @Test fun `canonicalForLoop ignores query and fragment`() {
        val a = RedirectSafety.canonicalForLoop("https://cdn.example.com/a?token=xyz#frag")
        val b = RedirectSafety.canonicalForLoop("https://cdn.example.com/a?other=1")
        assertEquals(a, b)
        assertTrue(a.endsWith("/a"))
    }

    @Test fun `absolute location resolves to itself`() {
        val resolved = RedirectSafety.resolveRelative("https://app.example/start", "https://cdn.example.com/next")
        assertEquals("https://cdn.example.com/next", resolved)
    }

    @Test fun `malformed location returns null`() {
        assertNull(RedirectSafety.resolveRelative("https://app.example/", ":::not a url:::"))
    }

    @Test fun `hop index below cap with valid target is allowed`() {
        val verdict = RedirectSafety.validate(
            "https://cdn.example.com/b", "https://app.example/", RedirectSafety.MAX_REDIRECT_HOPS - 1, emptySet()
        )
        assertTrue(verdict.allowed)
    }
}
