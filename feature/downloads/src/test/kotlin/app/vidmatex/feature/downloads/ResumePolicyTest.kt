package app.vidmatex.feature.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [ResumePolicy] (contract task 2). The critical invariant:
 * **no resume/append is ever claimed without a real 206 Partial Content response or a
 * prior `Accept-Ranges: bytes` probe** — a 200 OK on a Range request always restarts
 * from zero and never appends onto the partial file.
 */
class ResumePolicyTest {

    // ---- decide(): no partial bytes = fresh GET, never a Range resume ----
    @Test
    fun noPartialBytes_alwaysFreshGet_evenOn206() {
        // A 206 with zero existing bytes is still treated as a fresh body (no append).
        assertEquals(ResumePolicy.Decision.FRESH_GET, ResumePolicy.decide(0L, 206, "bytes"))
        assertEquals(ResumePolicy.Decision.FRESH_GET, ResumePolicy.decide(0L, 200, "bytes"))
    }

    // ---- decide(): 206 = APPEND (the only resume claim) ----
    @Test
    fun partialBytes_206Response_appends() {
        val decision = ResumePolicy.decide(1024L, 206, "bytes")
        assertEquals(ResumePolicy.Decision.APPEND, decision)
        assertTrue(ResumePolicy.appends(decision))
    }

    // ---- decide(): 200 (server ignored Range) = restart, NEVER append ----
    @Test
    fun partialBytes_200Response_restartsFromZero_neverAppends() {
        val decision = ResumePolicy.decide(1024L, 200, null)
        assertEquals(ResumePolicy.Decision.RESTART_FROM_ZERO, decision)
        assertFalse(ResumePolicy.appends(decision))
    }

    @Test
    fun partialBytes_200Response_evenWithAcceptRanges_restarts() {
        // Even if the server advertises Accept-Ranges, an actual 200 (not 206) means it
        // ignored the Range header and returned the full body: appending would corrupt it.
        val decision = ResumePolicy.decide(1024L, 200, "bytes")
        assertEquals(ResumePolicy.Decision.RESTART_FROM_ZERO, decision)
        assertFalse(ResumePolicy.appends(decision))
    }

    // ---- decide(): 416 Range Not Satisfiable = restart ----
    @Test
    fun partialBytes_416Response_restartsFromZero() {
        val decision = ResumePolicy.decide(1024L, 416, "bytes")
        assertEquals(ResumePolicy.Decision.RESTART_FROM_ZERO, decision)
        assertFalse(ResumePolicy.appends(decision))
    }

    // ---- decide(): other error codes = unsupported (retry classifier decides) ----
    @Test
    fun partialBytes_serverError_unsupportedRange() {
        val decision = ResumePolicy.decide(1024L, 500, null)
        assertEquals(ResumePolicy.Decision.UNSUPPORTED_RANGE, decision)
        assertFalse(ResumePolicy.appends(decision))
    }

    // ---- probeAllowed(): resume claim requires track flag OR real Accept-Ranges: bytes ----
    @Test
    fun probeAllowed_trackResumableTrue_allowedWithoutProbe() {
        assertTrue(ResumePolicy.probeAllowed(trackResumable = true, acceptRangesHeader = null))
    }

    @Test
    fun probeAllowed_trackUnknown_withBytesHeader_allowed() {
        assertTrue(ResumePolicy.probeAllowed(trackResumable = null, acceptRangesHeader = "bytes"))
    }

    @Test
    fun probeAllowed_trackUnknown_withoutBytesHeader_notAllowed_noResumeClaim() {
        // The critical negative assertion: no Accept-Ranges = no Range header sent.
        assertFalse(ResumePolicy.probeAllowed(trackResumable = null, acceptRangesHeader = null))
        assertFalse(ResumePolicy.probeAllowed(trackResumable = null, acceptRangesHeader = "none"))
        assertFalse(ResumePolicy.probeAllowed(trackResumable = false, acceptRangesHeader = ""))
    }

    @Test
    fun probeAllowed_trackFalse_withBytesHeader_allowed() {
        // A false flag can be overridden by a real probe response (re-probe on resume).
        assertTrue(ResumePolicy.probeAllowed(trackResumable = false, acceptRangesHeader = "bytes"))
    }

    // ---- acceptRangesAllowed: only "bytes" units count ----
    @Test
    fun acceptRangesAllowed_onlyBytesUnit() {
        assertTrue(ResumePolicy.acceptRangesAllowed("bytes"))
        assertTrue(ResumePolicy.acceptRangesAllowed("BYTES"))
        assertFalse(ResumePolicy.acceptRangesAllowed("none"))
        assertFalse(ResumePolicy.acceptRangesAllowed(null))
        assertFalse(ResumePolicy.acceptRangesAllowed(""))
    }
}
