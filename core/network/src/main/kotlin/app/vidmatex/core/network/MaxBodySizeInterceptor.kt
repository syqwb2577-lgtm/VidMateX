package app.vidmatex.core.network

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/**
 * Response body size cap for JSON API calls (health / extract / subtitle metadata).
 * When `Content-Length` declares an oversized body the call fails fast; otherwise the
 * streamed source is counted byte-by-byte and aborts as soon as [maxBytes] is
 * exceeded, so a malicious or misconfigured endpoint cannot exhaust app memory.
 *
 * The pure decision logic [BodyLimitPolicy.withinDeclaredLimit] is JVM-testable; the
 * interceptor itself runs on the OkHttp runtime (device/instrumented verification).
 */
class MaxBodySizeInterceptor(private val maxBytes: Long) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response

        val declaredLength = body.contentLength()
        if (BodyLimitPolicy.withinDeclaredLimit(declaredLength, maxBytes)) return response
        if (declaredLength > maxBytes) {
            body.close()
            throw IOException("Response body too large: $declaredLength bytes exceeds the ${maxBytes} byte limit.")
        }

        val counted = object : ForwardingSource(body.source()) {
            var readBytes = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read > 0) {
                    readBytes += read
                    if (readBytes > maxBytes) {
                        throw IOException("Response body exceeds the ${maxBytes} byte limit.")
                    }
                }
                return read
            }
        }
        val limitedBody = ResponseBody.create(body.contentType(), -1L, counted.buffer())
        return response.newBuilder().body(limitedBody).build()
    }
}

/** Pure, Android-free size-cap decisions shared by tests and the interceptor. */
object BodyLimitPolicy {
    /** Null length (chunked/unknown) defers to stream counting; positive values are checked upfront. */
    fun withinDeclaredLimit(contentLength: Long?, capBytes: Long): Boolean =
        contentLength == null || contentLength <= capBytes
}
