package app.vidmatex.core.network

import app.vidmatex.core.common.UrlValidator
import java.net.URI

/**
 * Redirect policy for the client HTTP stack (CONTRACTS §10; mirrors the backend
 * `ssrf_guard.py` manual redirect loop).
 *
 * Policy:
 *  1. OkHttp is built with automatic redirect follow DISABLED; [RedirectFollowInterceptor]
 *     follows hops manually and calls [validate] on every `Location` before issuing the
 *     next request.
 *  2. Every redirect target must pass [UrlValidator.isRemoteFetchAllowed]: https only,
 *     structurally valid host, no credentials in the authority, no control characters,
 *     and no private/loopback/link-local/metadata IP literal (SSRF defense-in-depth).
 *  3. Relative `Location` headers are resolved against the current request URL.
 *  4. Max [MAX_REDIRECT_HOPS] hops; revisiting a canonical URL aborts the loop.
 *
 * DNS-REBINDING / TOCTOU CAVEAT (honest limitation, mirrors SECURITY.md §7.1): this
 * check validates the hostname/literal before OkHttp connects; OkHttp performs its own
 * DNS resolution at connect time, so a hostname could theoretically resolve to a
 * public IP at validation time and a private address at connect time (DNS rebinding).
 * Mitigations on the client side: direct media URLs are additionally checked by
 * `DirectUrlAdapter.rejectPrivateHost`; backend endpoints are user-configured over
 * TLS; operators should deny private ranges at the egress firewall for fully
 * TOCTOU-safe deployments. The app does not implement kernel-level IP pinning.
 */
object RedirectSafety {
    /** Maximum manual redirect hops before the chain is aborted. */
    const val MAX_REDIRECT_HOPS = 5

    /** 3xx statuses the manual follower handles; other statuses are returned to the caller. */
    private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

    fun isRedirectStatus(code: Int): Boolean = code in REDIRECT_STATUSES

    /** Resolves a possibly-relative [location] against [refererRaw]; null on malformed input. */
    fun resolveRelative(refererRaw: String, location: String): String? = runCatching {
        URI(refererRaw.trim()).resolve(location.trim()).toString()
    }.getOrNull()

    /** Loop-detection key: scheme + host + port + path, query/fragment ignored. */
    fun canonicalForLoop(url: String): String = runCatching {
        val u = URI(url)
        URI(u.scheme, null, u.host, u.port, u.path, null, null).toString().lowercase()
    }.getOrDefault(url.trim().lowercase())

    /**
     * Validates one redirect hop. [hopIndex] is the zero-based number of redirects
     * already taken; [seen] holds canonical keys of previous hops in this chain.
     * Pure JVM logic (no OkHttp calls) so every rule is unit-testable.
     */
    fun validate(targetRaw: String, refererRaw: String, hopIndex: Int, seen: Set<String>): RedirectVerdict {
        if (hopIndex >= MAX_REDIRECT_HOPS) {
            return RedirectVerdict(false, "Too many redirects (maximum $MAX_REDIRECT_HOPS).")
        }
        val resolved = resolveRelative(refererRaw, targetRaw)
            ?: return RedirectVerdict(false, "Redirect Location could not be resolved against the current URL.")
        if (!UrlValidator.isRemoteFetchAllowed(resolved)) {
            return RedirectVerdict(
                false,
                "Redirect rejected: target must be an https URL with a public host and no credentials.",
            )
        }
        if (canonicalForLoop(resolved) in seen) {
            return RedirectVerdict(false, "Redirect loop detected: target was already visited.")
        }
        return RedirectVerdict(true)
    }
}

/** Outcome of one redirect validation. [reason] is a plain, non-secret explanation. */
data class RedirectVerdict(val allowed: Boolean, val reason: String? = null)
