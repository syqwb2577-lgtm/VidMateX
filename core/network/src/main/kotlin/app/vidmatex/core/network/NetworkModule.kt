package app.vidmatex.core.network

import app.vidmatex.core.common.UrlValidator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * SECURITY LOGGING / SECRET POLICY (applies to this module and every network component):
 * 1. [HTTP_LOG_LEVEL] stays NONE in every build; BODY or HEADERS logging must never be
 *    enabled (it would capture the X-API-Key header, query credentials, and bodies).
 * 2. [ApiKeyInterceptor] attaches `X-API-Key` but contains NO logging, NO println, NO
 *    error-body echo of the key, and must never be extended to log the header.
 * 3. Base URLs are https-only via [BackendUrlValidator]; plain http returns null, and
 *    private/loopback/link-local hosts are rejected ([UrlValidator.isLikelyPrivateHost]).
 * 4. Error messages surfaced to the UI come from ApiSafety.toBackendException and pass
 *    through SecretTextRedactor; they never embed the API key.
 * 5. Any URL that must be logged for diagnostics goes through [UrlValidator.redactForLog],
 *    which strips userinfo and redacts sensitive query parameters.
 *
 * buildConfig is disabled project-wide, so debug-vs-release is not available at runtime;
 * the safe default below (NONE) is therefore the shipped default.
 */
private val HTTP_LOG_LEVEL = HttpLoggingInterceptor.Level.NONE

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    // --- Timeouts (distinct connect / read / write; mirrors backend ssrf_guard timeouts) ---
    const val CONNECT_TIMEOUT_SEC = 30L
    const val READ_TIMEOUT_SEC = 60L
    const val WRITE_TIMEOUT_SEC = 60L

    /**
     * Hard cap for JSON API responses built by [RetrofitFactory] (health / extract /
     * subtitle metadata). A body declared or streamed beyond this size aborts the call;
     * see [MaxBodySizeInterceptor]. Large media byte streams use the shared client
     * directly and are deliberately NOT subject to this JSON cap.
     */
    const val MAX_API_RESPONSE_BYTES = 1L * 1024 * 1024 // 1 MiB

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    /**
     * Shared transport client (used by direct-media probes and download streams):
     * hardened timeouts + release-safe logging only. Redirect policy and the JSON body
     * cap are applied per-call on Retrofit clients in [RetrofitFactory.create] so media
     * byte streams are never truncated by an API-sized cap.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply { level = HTTP_LOG_LEVEL }
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofitFactory(client: OkHttpClient, json: Json): RetrofitFactory =
        RetrofitFactory(client, json)
}

class RetrofitFactory(private val client: OkHttpClient, private val json: Json) {
    /** Returns null when baseUrl is blank or invalid (feature = "not configured"). */
    fun create(baseUrl: String): Retrofit? = create(baseUrl, client)

    /**
     * Builds a Retrofit against [baseUrl]. The returned client is a per-call clone of
     * [client] with the API-hardening interceptors applied:
     *  - automatic redirects disabled; [RedirectFollowInterceptor] re-validates every
     *    `Location` through [RedirectSafety] (https + public host, no credentials, hop
     *    cap, loop detection) before following;
     *  - [MaxBodySizeInterceptor] enforces [NetworkModule.MAX_API_RESPONSE_BYTES] on
     *    JSON bodies;
     *  - the shared logging interceptor (level NONE) remains installed.
     *
     * Backend resolver endpoints are required to be https and publicly reachable:
     * [BackendUrlValidator] enforces scheme + host presence, and [UrlValidator.isLikelyPrivateHost]
     * rejects loopback/private/link-local/metadata literals so a misconfigured endpoint
     * cannot drive SSRF from the device. Malformed/host-less/private URLs return null and
     * callers surface a clear "invalid https URL" message.
     */
    fun create(baseUrl: String, client: OkHttpClient): Retrofit? {
        val httpUrl = BackendUrlValidator.toHttpsBaseUrl(baseUrl) ?: return null
        if (UrlValidator.isLikelyPrivateHost(httpUrl.host)) return null
        val apiClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(RedirectFollowInterceptor())
            .addInterceptor(MaxBodySizeInterceptor(NetworkModule.MAX_API_RESPONSE_BYTES))
            .build()
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(httpUrl)
            .client(apiClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    /**
     * Backend API with optional shared-secret auth. When [apiKey] is non-blank an
     * `X-API-Key` header is added to every request via a per-call interceptor on a
     * cloned client (the shared singleton client is never mutated); the API safety
     * interceptors from [create] are applied on top of that clone.
     */
    fun backendApi(baseUrl: String, apiKey: String = ""): BackendApi? {
        val keyClient = if (apiKey.isNotBlank()) {
            client.newBuilder().addInterceptor(ApiKeyInterceptor(apiKey)).build()
        } else {
            client
        }
        return create(baseUrl, keyClient)?.create(BackendApi::class.java)
    }

    fun libreTranslateApi(baseUrl: String): LibreTranslateApi? = create(baseUrl)?.create(LibreTranslateApi::class.java)
}

/**
 * Injects the `X-API-Key` shared-secret header when [apiKey] is non-blank; otherwise
 * the request passes through unchanged. The key is held in memory only for the lifetime
 * of this cloned client and is never persisted, logged, or echoed into error messages.
 */
class ApiKeyInterceptor(private val apiKey: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = if (apiKey.isNotBlank()) {
            chain.request().newBuilder().addHeader("X-API-Key", apiKey).build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
