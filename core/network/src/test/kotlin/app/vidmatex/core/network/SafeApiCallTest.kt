package app.vidmatex.core.network

import app.vidmatex.core.common.AppResult
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Tests the pure [toBackendException] mapping used by [safeApiCall]. No network or
 * Retrofit instance is needed — exceptions are constructed directly.
 */
class SafeApiCallTest {

    @Test fun `socket timeout maps to Timeout with clear message`() {
        val be = toBackendException(SocketTimeoutException("read timed out"))
        assertTrue(be is BackendException.Timeout)
        assertTrue(be.message!!.contains("timed out", ignoreCase = true))
    }

    @Test fun `unknown host maps to NoNetwork`() {
        val be = toBackendException(UnknownHostException("example.com"))
        assertTrue(be is BackendException.NoNetwork)
    }

    @Test fun `connect exception maps to NoNetwork`() {
        val be = toBackendException(ConnectException("failed to connect"))
        assertTrue(be is BackendException.NoNetwork)
    }

    @Test fun `http 404 maps to HttpError with code`() {
        val httpEx = HttpException(Response.error<String>(404, "".toResponseBody(null)))
        val be = toBackendException(httpEx)
        assertTrue(be is BackendException.HttpError)
        assertEquals(404, (be as BackendException.HttpError).code)
        assertTrue(be.message!!.contains("404"))
    }

    @Test fun `http 500 with backend error body extracts human message`() {
        val body = """{"error":{"code":"PLATFORM_NOT_SUPPORTED","message":"This platform is not supported yet."}}"""
        val httpEx = HttpException(Response.error<String>(500, body.toResponseBody("application/json".toMediaTypeOrNull())))
        val be = toBackendException(httpEx)
        assertTrue(be is BackendException.HttpError)
        assertEquals(500, (be as BackendException.HttpError).code)
        assertTrue(be.message!!.contains("not supported yet"))
    }

    @Test fun `illegal argument maps to InvalidRequest`() {
        val be = toBackendException(IllegalArgumentException("baseUrl must end in /"))
        assertTrue(be is BackendException.InvalidRequest)
    }

    @Test fun `generic io exception maps to Unknown with message`() {
        val be = toBackendException(java.io.IOException("stream reset"))
        assertTrue(be is BackendException.Unknown)
        assertTrue(be.message!!.contains("stream reset"))
    }

    @Test fun `already a BackendException passes through unchanged`() {
        val original = BackendException.Timeout("t")
        assertEquals(original, toBackendException(original))
    }

    @Test fun `http 401 maps to Unauthorized and is not recoverable`() = runBlocking {
        val httpEx = HttpException(Response.error<String>(401, "".toResponseBody(null)))
        val be = toBackendException(httpEx)
        assertTrue(be is BackendException.Unauthorized)
        assertEquals(401, (be as BackendException.HttpError).code)
        val result = safeApiCall<String> { throw httpEx }
        assertTrue(result is AppResult.Error)
        assertFalse((result as AppResult.Error).recoverable)
    }

    @Test fun `http 429 maps to RateLimited and is recoverable`() = runBlocking {
        val httpEx = HttpException(Response.error<String>(429, "".toResponseBody(null)))
        val be = toBackendException(httpEx)
        assertTrue(be is BackendException.RateLimited)
        assertEquals(429, (be as BackendException.HttpError).code)
        val result = safeApiCall<String> { throw httpEx }
        assertTrue((result as AppResult.Error).recoverable)
    }

    @Test fun `malformed json maps to MalformedBody instead of crashing`() {
        val be = toBackendException(SerializationException("Unexpected JSON token at offset 0"))
        assertTrue(be is BackendException.MalformedBody)
    }

    @Test fun `server error body with embedded token is redacted in the message`() {
        val body = """{"error":{"code":"AUTH","message":"rejected key=AbCdef1234567890XYZ99"}}"""
        val httpEx = HttpException(Response.error<String>(500, body.toResponseBody("application/json".toMediaTypeOrNull())))
        val be = toBackendException(httpEx)
        assertTrue(be is BackendException.HttpError)
        assertFalse(be.message!!.contains("AbCdef1234567890XYZ99"))
    }

    @Test fun `backend error code is extracted into serverErrorCode`() {
        val body = """{"error":{"code":"SSRF_BLOCKED","message":"URL rejected by SSRF guard."}}"""
        val httpEx = HttpException(Response.error<String>(403, body.toResponseBody("application/json".toMediaTypeOrNull())))
        val be = toBackendException(httpEx)
        assertTrue(be is BackendException.HttpError)
        assertEquals("SSRF_BLOCKED", (be as BackendException.HttpError).serverErrorCode)
        assertTrue(be.message!!.contains("SSRF guard"))
    }

    @Test fun `error body without code yields null serverErrorCode`() {
        val body = """{"error":{"message":"plain message only"}}"""
        val httpEx = HttpException(Response.error<String>(500, body.toResponseBody("application/json".toMediaTypeOrNull())))
        val be = toBackendException(httpEx)
        assertNull((be as BackendException.HttpError).serverErrorCode)
    }

    @Test fun `403 SSRF_BLOCKED is classified non-recoverable`() = runBlocking {
        val body = """{"error":{"code":"SSRF_BLOCKED","message":"blocked"}}"""
        val httpEx = HttpException(Response.error<String>(403, body.toResponseBody("application/json".toMediaTypeOrNull())))
        val result = safeApiCall<String> { throw httpEx }
        assertTrue(result is AppResult.Error)
        assertFalse((result as AppResult.Error).recoverable)
    }

    @Test fun `502 upstream error is recoverable`() = runBlocking {
        val httpEx = HttpException(Response.error<String>(502, "".toResponseBody(null)))
        val result = safeApiCall<String> { throw httpEx }
        assertTrue((result as AppResult.Error).recoverable)
    }

    @Test fun `400 invalid request is non-recoverable`() = runBlocking {
        val httpEx = HttpException(Response.error<String>(400, "".toResponseBody(null)))
        val result = safeApiCall<String> { throw httpEx }
        assertFalse((result as AppResult.Error).recoverable)
    }
}
