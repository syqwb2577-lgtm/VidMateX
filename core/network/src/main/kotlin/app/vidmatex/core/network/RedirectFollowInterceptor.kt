package app.vidmatex.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Manual, policy-checked redirect follower. Installed only on clients built with
 * `followRedirects(false)` (see [NetworkModule]): OkHttp does not follow 3xx itself;
 * this interceptor validates every `Location` through [RedirectSafety] before
 * issuing the next request, enforces the hop cap, and aborts loops.
 *
 * When a target fails policy (non-https, private/loopback host, credentials in URL,
 * malformed) the unsafe redirect is NOT followed and the original 3xx response is
 * returned to the caller, which surfaces as a non-success HTTP error through
 * `safeApiCall`.
 *
 * Runtime note: OkHttp/Interceptor execution requires an Android/instrumented
 * environment; the validation rules themselves live in the pure [RedirectSafety]
 * object and are covered by plain JUnit tests.
 */
class RedirectFollowInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        val seen = LinkedHashSet<String>()
        var hops = 0

        while (RedirectSafety.isRedirectStatus(response.code)) {
            if (hops >= RedirectSafety.MAX_REDIRECT_HOPS) {
                response.close()
                throw IOException("Too many redirects (maximum ${RedirectSafety.MAX_REDIRECT_HOPS}).")
            }
            val location = response.header("Location")
            if (location.isNullOrBlank()) break

            val currentUrl = response.request.url.toString()
            val verdict = RedirectSafety.validate(location, currentUrl, hops, seen)
            if (!verdict.allowed) {
                // Leave the 3xx response untouched; never follow an unsafe target.
                return response
            }
            val nextUrl = RedirectSafety.resolveRelative(currentUrl, location) ?: break

            seen.add(RedirectSafety.canonicalForLoop(currentUrl))
            response.close()
            hops += 1
            request = request.newBuilder().url(nextUrl).build()
            response = chain.proceed(request)
        }
        return response
    }
}
