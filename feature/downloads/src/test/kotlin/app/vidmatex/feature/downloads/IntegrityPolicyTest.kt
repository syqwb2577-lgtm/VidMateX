package app.vidmatex.feature.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Pure JVM tests for [IntegrityPolicy] (requirement §8: size verdict + Content-MD5 check).
 * No Android imports. Source-ready pending CI.
 */
class IntegrityPolicyTest {

    private fun md5Header(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes))

    // ---- size verdicts ----
    @Test
    fun `size match ok, mismatch failure, missing size unverifiable`() {
        assertEquals(IntegrityVerdict.OK, IntegrityPolicy.verifySize(1_000L, 1_000L))
        assertEquals(IntegrityVerdict.SIZE_MISMATCH, IntegrityPolicy.verifySize(1_000L, 999L))
        assertEquals(IntegrityVerdict.SIZE_MISMATCH, IntegrityPolicy.verifySize(1_000L, 1_500L))
        assertEquals(IntegrityVerdict.MISSING_SIZE, IntegrityPolicy.verifySize(null, 500L))
        assertEquals(IntegrityVerdict.MISSING_SIZE, IntegrityPolicy.verifySize(0L, 500L))
    }

    @Test
    fun `only size or checksum mismatch is a hard failure`() {
        assertTrue(IntegrityPolicy.isFailure(IntegrityVerdict.SIZE_MISMATCH))
        assertTrue(IntegrityPolicy.isFailure(IntegrityVerdict.CHECKSUM_MISMATCH))
        assertFalse(IntegrityPolicy.isFailure(IntegrityVerdict.OK))
        assertFalse(IntegrityPolicy.isFailure(IntegrityVerdict.MISSING_SIZE))
        assertFalse(IntegrityPolicy.isFailure(IntegrityVerdict.CHECKSUM_UNAVAILABLE))
    }

    // ---- Content-MD5 ----
    @Test
    fun `valid base64 16 byte header parsed, malformed or wrong length rejected`() {
        val header = md5Header("abc".toByteArray())
        assertEquals(16, IntegrityPolicy.parseContentMd5(header)?.size)
        assertNull(IntegrityPolicy.parseContentMd5(null))
        assertNull(IntegrityPolicy.parseContentMd5(""))
        assertNull(IntegrityPolicy.parseContentMd5("not-base64!!"))
        assertNull(IntegrityPolicy.parseContentMd5(Base64.getEncoder().encodeToString(ByteArray(8))))
    }

    @Test
    fun `matching content md5 verifies, tampered bytes mismatch`() {
        val body = "video-bytes".toByteArray()
        val header = md5Header(body)
        assertEquals(IntegrityVerdict.OK, IntegrityPolicy.verifyContentMd5(header, body))
        assertEquals(
            IntegrityVerdict.CHECKSUM_MISMATCH,
            IntegrityPolicy.verifyContentMd5(header, "video-bytez".toByteArray()),
        )
    }

    @Test
    fun `absent checksum header means unavailable not ok`() {
        assertEquals(
            IntegrityVerdict.CHECKSUM_UNAVAILABLE,
            IntegrityPolicy.verifyContentMd5(null, "abc".toByteArray()),
        )
    }
}
