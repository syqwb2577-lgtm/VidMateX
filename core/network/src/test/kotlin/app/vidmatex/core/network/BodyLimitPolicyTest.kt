package app.vidmatex.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [BodyLimitPolicy] (the OkHttp interceptor itself is runtime-side). */
class BodyLimitPolicyTest {

    @Test fun `unknown content length defers to stream counting`() {
        assertTrue(BodyLimitPolicy.withinDeclaredLimit(null, 1024))
    }

    @Test fun `declared length under cap is allowed`() {
        assertTrue(BodyLimitPolicy.withinDeclaredLimit(512L, 1024L))
        assertTrue(BodyLimitPolicy.withinDeclaredLimit(1024L, 1024L))
    }

    @Test fun `declared length over cap is rejected upfront`() {
        assertFalse(BodyLimitPolicy.withinDeclaredLimit(4096L, 1024L))
    }

    @Test fun `zero-length body is always allowed`() {
        assertTrue(BodyLimitPolicy.withinDeclaredLimit(0L, 1024L))
        assertTrue(BodyLimitPolicy.withinDeclaredLimit(0L, 0L))
    }

    @Test fun `negative declared length is treated as unknown (chunked)`() {
        // OkHttp returns -1 when Content-Length is absent; treat as unknown/streaming.
        assertTrue(BodyLimitPolicy.withinDeclaredLimit(-1L, 1024L))
    }

    @Test fun `cap exactly equals declared length is allowed`() {
        assertTrue(BodyLimitPolicy.withinDeclaredLimit(1_000_000L, 1_000_000L))
    }
}
