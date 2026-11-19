package app.vidmatex.feature.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for [RangeHeaderPolicy] header parsing (contract task 2). */
class RangeHeaderPolicyTest {

    @Test
    fun acceptsBytes_bytesUnit_true() {
        assertTrue(RangeHeaderPolicy.acceptsBytes("bytes"))
        assertTrue(RangeHeaderPolicy.acceptsBytes("BYTES"))
    }

    @Test
    fun acceptsBytes_nonBytesOrNull_false() {
        assertFalse(RangeHeaderPolicy.acceptsBytes("none"))
        assertFalse(RangeHeaderPolicy.acceptsBytes(null))
        assertFalse(RangeHeaderPolicy.acceptsBytes(""))
    }

    @Test
    fun parseContentRangeTotal_valid_returnsTotal() {
        assertEquals(1024L, RangeHeaderPolicy.parseContentRangeTotal("bytes 0-1023/1024"))
        assertEquals(200000L, RangeHeaderPolicy.parseContentRangeTotal("bytes 500-199999/200000"))
    }

    @Test
    fun parseContentRangeTotal_unknownTotal_returnsNull() {
        assertNull(RangeHeaderPolicy.parseContentRangeTotal("bytes 0-1023/*"))
    }

    @Test
    fun parseContentRangeTotal_malformed_returnsNull() {
        assertNull(RangeHeaderPolicy.parseContentRangeTotal(null))
        assertNull(RangeHeaderPolicy.parseContentRangeTotal(""))
        assertNull(RangeHeaderPolicy.parseContentRangeTotal("bytes 0-1023/abc"))
        assertNull(RangeHeaderPolicy.parseContentRangeTotal("no-slash-here"))
    }

    @Test
    fun totalForResumedResponse_contentRangeWins() {
        val total = RangeHeaderPolicy.totalForResumedResponse(
            contentRangeHeader = "bytes 100-199/1000",
            existingBytes = 100L,
            bodyContentLength = 900L,
        )
        assertEquals(1000L, total)
    }

    @Test
    fun totalForResumedResponse_noContentRange_fallsBackToExistingPlusBody() {
        val total = RangeHeaderPolicy.totalForResumedResponse(
            contentRangeHeader = null,
            existingBytes = 100L,
            bodyContentLength = 900L,
        )
        assertEquals(1000L, total)
    }

    @Test
    fun totalForResumedResponse_negativeBodyLengthAndNoRange_null() {
        assertNull(
            RangeHeaderPolicy.totalForResumedResponse(
                contentRangeHeader = null,
                existingBytes = 100L,
                bodyContentLength = -1L,
            ),
        )
    }

    @Test
    fun parseContentLength_validAndInvalid() {
        assertEquals(1024L, RangeHeaderPolicy.parseContentLength("1024"))
        assertNull(RangeHeaderPolicy.parseContentLength(null))
        assertNull(RangeHeaderPolicy.parseContentLength("abc"))
        assertNull(RangeHeaderPolicy.parseContentLength("-5"))
    }
}
