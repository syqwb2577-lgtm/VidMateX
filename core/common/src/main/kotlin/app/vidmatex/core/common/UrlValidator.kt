package app.vidmatex.core.common

import android.util.Patterns
import app.vidmatex.core.model.SourceType
import java.net.URI

/**
 * Secure URL validation + source detection.
 *
 * Scheme policy (CONTRACTS §10 / SECURITY.md §3):
 *  - Remote fetches (direct media, platform metadata URLs) are untrusted input:
 *    `http`/`https` structural allowlist, but the strict remote gate
 *    [isRemoteFetchAllowed] requires **https**, rejects credentials in the URL,
 *    control characters, malformed hosts, and private/loopback/link-local/metadata
 *    IP literals (SSRF defense-in-depth; the backend resolver performs DNS-based
 *    SSRF validation for platform links).
 *  - `file` and `content` schemes are NEVER accepted by remote validation
 *    ([hasBlockedScheme] / [structuralCheck]). They are accepted ONLY through
 *    [isAllowedLocalMediaUri], and only when the path/authority belongs to
 *    app-owned local media (caller-supplied roots / provider authority allowlist).
 *
 * Structure:
 *  - [structuralCheck] / [hasBlockedScheme] / [isAllowedScheme] / [isLikelyPrivateHost]
 *    / [isRemoteFetchAllowed] / [isAllowedLocalMediaUri] are PURE JVM logic
 *    (no Android imports) so they can be covered by local unit tests.
 *  - [isValid] applies [structuralCheck] plus the device-side [Patterns.WEB_URL]
 *    regex (android.util.Patterns is unavailable in plain JVM tests).
 */
object UrlValidator {
    /** Schemes that are always rejected for remote fetches, before normalization. */
    private val BLOCKED_SCHEMES = setOf("javascript", "file", "data", "content", "ftp", "sms", "tel", "intent")

    /** Strict remote policy requires https; legacy [structuralCheck] still permits http for callers that enforce transport security elsewhere. */
    private const val SCHEME_HTTPS = "https"

    /** Maximum length of one URL label (DNS host label, RFC 1035). */
    private const val MAX_HOST_LABEL_LENGTH = 63
    /** Maximum total hostname length (RFC 1035 / RFC 5890). */
    private const val MAX_HOST_TOTAL_LENGTH = 253

    /**
     * Full device-side validity check: pure structural rules + Android web-URL regex.
     * http/https structural allowlist; host present; no javascript:/file:/data:/etc.;
     * no userinfo; no control characters; no private/metadata IP literals.
     */
    fun isValid(url: String): Boolean {
        if (!structuralCheck(url)) return false
        return Patterns.WEB_URL.matcher(url.trim()).matches()
    }

    /**
     * Strict remote-fetch gate: https-only, structurally valid, public host, no
     * credentials, no control characters. Used by direct-media fetch paths before
     * opening a connection. Platform-link resolution still goes through the backend
     * resolver (which does its own DNS-based SSRF validation).
     */
    fun isRemoteFetchAllowed(url: String): Boolean {
        val trimmed = url.trim()
        if (!structuralCheck(trimmed)) return false
        val scheme = runCatching { URI(normalize(trimmed)).scheme?.lowercase() }.getOrNull()
        return scheme == SCHEME_HTTPS
    }

    /**
     * Local-media gate (the ONLY path that accepts `file:` / `content:` URIs).
     *
     * @param allowedFileRoots absolute prefixes (e.g. app-private cache/files dirs);
     *   a `file://` path must equal one root or sit beneath it; `..` segments are rejected.
     * @param allowedContentAuthorities FileProvider/media authorities owned by the app;
     *   empty set rejects every `content://` URI.
     * @return true only for a well-formed local URI under an explicitly allowed scope.
     */
    fun isAllowedLocalMediaUri(
        raw: String,
        allowedFileRoots: List<String> = emptyList(),
        allowedContentAuthorities: Set<String> = emptySet(),
    ): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || containsControlChars(trimmed)) return false
        val schemeSeparator = trimmed.indexOf("://")
        if (schemeSeparator <= 0) return false
        val scheme = trimmed.substring(0, schemeSeparator).lowercase()
        return when (scheme) {
            "file" -> {
                val path = runCatching { URI(trimmed).path }.getOrNull() ?: return false
                if (hasTraversalSegment(path)) return false
                val roots = allowedFileRoots.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
                roots.any { root -> path == root || path.startsWith("$root/") }
            }
            "content" -> {
                val authority = runCatching { URI(trimmed).authority }.getOrNull() ?: return false
                if (authority !in allowedContentAuthorities) return false
                // No userinfo on content URIs either.
                !trimmed.substring(schemeSeparator + 3).substringBefore('/').contains('@')
            }
            else -> false
        }
    }

    /**
     * Pure-JVM structural validation (no Android dependency). Kept package-internal so
     * unit tests can exercise every rule without Robolectric. [isValid] additionally
     * runs the Android URL regex as a defense-in-depth device-side check.
     */
    internal fun structuralCheck(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.length > Constants.MAX_URL_LENGTH) return false
        // Reject explicitly dangerous schemes before any normalization.
        if (hasBlockedScheme(trimmed)) return false
        // Reject ASCII control characters (tab, CR/LF, NUL, …) anywhere in the URL.
        if (containsControlChars(trimmed)) return false
        // Reject credentials-in-URL patterns even when the URI parser would tolerate them.
        if (hasUserInfo(trimmed)) return false
        val schemeSeparator = trimmed.indexOf("://")
        if (schemeSeparator > 0) {
            val scheme = trimmed.substring(0, schemeSeparator).lowercase()
            if (!isAllowedScheme(scheme)) return false
        }
        return runCatching {
            val uri = URI(normalize(trimmed))
            isAllowedScheme(uri.scheme?.lowercase()) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                isValidHostShape(uri.host) &&
                // SSRF defense-in-depth: no loopback/private/link-local/metadata literals
                // in a URL that the app might fetch directly.
                !isLikelyPrivateHost(uri.host)
        }.getOrDefault(false)
    }

    /** True when [url] carries a scheme that must never be fetched (javascript:, file:, …). */
    internal fun hasBlockedScheme(url: String): Boolean {
        val sep = url.indexOf("://")
        if (sep <= 0) {
            // javascript:alert(1) style without "://" still needs blocking when present.
            val colon = url.indexOf(':')
            if (colon in 1..16) {
                val scheme = url.substring(0, colon).lowercase()
                if (scheme in BLOCKED_SCHEMES) return true
            }
            return false
        }
        val scheme = url.substring(0, sep).lowercase()
        return scheme in BLOCKED_SCHEMES
    }

    /** http/https only (case-insensitive). */
    internal fun isAllowedScheme(scheme: String?): Boolean =
        scheme != null && scheme.lowercase() in Constants.ALLOWED_SCHEMES

    /** True when the authority portion carries userinfo (`user:pass@host` / `user@host`). */
    internal fun hasUserInfo(raw: String): Boolean {
        val sep = raw.indexOf("://")
        val authority = if (sep >= 0) {
            val after = raw.substring(sep + 3)
            after.substringBefore('/').substringBefore('?').substringBefore('#')
        } else {
            raw
        }
        return authority.contains('@')
    }

    /** True when [s] contains an ASCII control character (incl. tab, CR, LF, NUL, DEL). */
    internal fun containsControlChars(s: String): Boolean =
        s.any { it.code in 0x00..0x1F || it.code == 0x7F }

    /**
     * Host-shape validation: DNS labels 1..63 chars, `[A-Za-z0-9-]`, not starting or
     * ending with a hyphen; total ≤ 253 chars. IPv6 literals get a loose character
     * check (ranges are classified by [isLikelyPrivateHost]); IPv4 literals fall through
     * to [isLikelyPrivateHost], which treats malformed/oversized octets as unsafe.
     */
    internal fun isValidHostShape(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.removeSurrounding("[", "]").lowercase()
        if (h.length > MAX_HOST_TOTAL_LENGTH || h.contains(' ')) return false
        if (h.contains(':')) {
            return h.isNotBlank() && h.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch == ':' || ch == '.' || ch == '%' }
        }
        val labels = h.split('.')
        if (labels.any { it.isEmpty() || it.length > MAX_HOST_LABEL_LENGTH }) return false
        val labelRe = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
        return labels.all { it.matches(labelRe) }
    }

    fun normalize(url: String): String {
        val t = url.trim()
        // Dangerous schemes are never rewritten into https:// — leave them untouched so
        // [isValid] / [hasBlockedScheme] can reject them explicitly.
        if (hasBlockedScheme(t)) return t
        return if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) {
            t
        } else {
            "https://$t"
        }
    }

    fun detectSource(url: String): SourceType {
        val host = runCatching { URI(normalize(url)).host?.lowercase().orEmpty() }.getOrDefault("")
        return when {
            host.contains("youtube.com") || host.contains("youtu.be") -> SourceType.YOUTUBE
            host.contains("facebook.com") || host.contains("fb.watch") || host.contains("fb.com") -> SourceType.FACEBOOK
            host.contains("tiktok.com") -> SourceType.TIKTOK
            host.isNotBlank() -> SourceType.DIRECT_URL
            else -> SourceType.UNKNOWN
        }
    }

    /** Strip known tracking params to keep canonical source IDs stable (dedup). */
    fun canonicalize(url: String): String {
        val n = normalize(url)
        val strip = setOf("fbclid", "gclid", "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "feature", "si")
        return runCatching {
            val uri = URI(n)
            val q = uri.query ?: return@runCatching n
            val kept = q.split("&").filter { p -> p.substringBefore("=") !in strip }.joinToString("&")
            URI(uri.scheme, uri.authority, uri.path, kept.ifBlank { null }, null).toString()
        }.getOrDefault(n)
    }

    /**
     * Logging-safe URL: userinfo removed and sensitive query values (`api_key`, `token`,
     * `signature`, …) replaced with `[redacted]`. Never logs credentials; safe to call
     * from interceptors and error paths. Falls back to [SecretTextRedactor.redact] when
     * the URL cannot be parsed.
     */
    fun redactForLog(url: String): String {
        if (url.isBlank()) return url
        return runCatching {
            val uri = URI(url)
            val sensitive = setOf("api_key", "key", "token", "access_token", "secret", "signature", "password", "authorization")
            val query = uri.query
            val rebuilt = URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
            if (query.isNullOrBlank()) return@runCatching rebuilt
            val kept = query.split("&").joinToString("&") { pair ->
                val name = pair.substringBefore("=").lowercase()
                if (name in sensitive) "$name=[redacted]" else pair
            }
            if (kept.isBlank()) rebuilt else "$rebuilt?$kept"
        }.getOrElse { SecretTextRedactor.redact(url) }
    }

    /**
     * Client-side check for private / loopback / link-local / reserved hosts. Used to
     * reject direct downloads from internal addresses (SSRF defense-in-depth; the
     * backend resolver does full DNS-based SSRF validation for platform links).
     *
     * This is a best-effort hostname/IP-literal check — it does NOT perform DNS
     * resolution (a public hostname resolving to a private IP is the backend's job
     * to block). Returns true when the host is clearly private/reserved.
     */
    fun isLikelyPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim().removeSurrounding("[", "]")
        if (h.isEmpty()) return true
        // Special-use hostnames (RFC 6761 / RFC 6762 / mDNS / internal conventions).
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h.endsWith(".local") || h.endsWith(".localdomain")) return true
        if (h.endsWith(".internal") || h.endsWith(".intranet") || h.endsWith(".lan")) return true
        if (h.endsWith(".home") || h.endsWith(".host")) return true
        // IPv4 literal in private/loopback/link-local/reserved ranges.
        val v4 = IPV4_RE.find(h)
        if (v4 != null) {
            val (a, b, c, d) = v4.destructured
            val parts = listOf(a.toIntOrNull(), b.toIntOrNull(), c.toIntOrNull(), d.toIntOrNull())
            if (parts.any { it == null || it > 255 }) return true // malformed IP = treat as unsafe
            val (p0, p1) = parts[0]!! to parts[1]!!
            return when (p0) {
                0, 10, 127 -> true
                100 -> p1 in 64..127          // CGNAT 100.64.0.0/10
                169 -> p1 == 254               // link-local 169.254.0.0/16 (incl. 169.254.169.254 cloud metadata)
                172 -> p1 in 16..31            // 172.16.0.0/12
                192 -> p1 == 168 || (p1 == 0 && parts[2]!! == 2) // 192.168/16 + TEST-NET-1 192.0.2/24
                198 -> p1 in 18..19            // TEST-NET 198.18/15
                    || (p1 == 51 && parts[2]!! == 100)
                203 -> p1 == 0 && parts[2]!! == 113 // TEST-NET-3 203.0.113/24
                else -> p0 >= 224               // multicast (224/4) + reserved (>=240)
            }
        }
        // IPv6 literal: loopback ::1, unspecified ::, link-local fe80::/10,
        // unique-local fc00::/7, documentation 2001:db8::/32 (RFC 3849), multicast ff00::/8.
        // Literals always contain ':'; hostname false-positives (e.g. "fc.example.com")
        // are excluded by the contains(':') gate.
        if (h.contains(':')) {
            if (h == "::1" || h == "::") return true
            if (h.startsWith("fe80:") || h.startsWith("fe90:") || h.startsWith("fea0:") || h.startsWith("feb0:")) return true
            if (h.startsWith("fc") || h.startsWith("fd")) return true
            if (h.startsWith("2001:db8")) return true
            if (h.startsWith("ff")) return true
        }
        return false
    }

    /** Segment-wise traversal check for a POSIX-style path: rejects any `..` segment. */
    internal fun hasTraversalSegment(path: String): Boolean =
        path.split('/', '\\').any { it == ".." }

    private val IPV4_RE = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
}
