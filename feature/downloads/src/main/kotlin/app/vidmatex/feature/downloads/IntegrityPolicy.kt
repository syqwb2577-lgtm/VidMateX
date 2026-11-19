package app.vidmatex.feature.downloads

import java.security.MessageDigest
import java.util.Base64

/**
 * Pure download-integrity verdicts (no Android imports; JVM-testable).
 *
 * Two independent checks (master requirement §8):
 *  1. **Length check**: final byte count vs the `Content-Length` (or `Content-Range`
 *     total) advertised by the server.
 *  2. **Checksum check**: when the source supplies a `Content-MD5` header (Base64 of the
 *     16-byte MD5 digest), compare it against the digest of the received bytes.
 *
 * A `MISSING_SIZE` / `CHECKSUM_UNAVAILABLE` result means "not verifiable", not "valid";
 * the worker only treats explicit [IntegrityVerdict.OK] as verified. Mismatches are
 * reported as FAILED jobs and never presented as completed downloads.
 */
internal enum class IntegrityVerdict {
    /** Length and (if present) checksum match. */
    OK,

    /** Received byte count differs from the advertised total. */
    SIZE_MISMATCH,

    /** Advertised size absent or non-positive; length cannot be verified. */
    MISSING_SIZE,

    /** MD5 digest differs from the Content-MD5 header. */
    CHECKSUM_MISMATCH,

    /** No checksum header supplied; checksum not verifiable. */
    CHECKSUM_UNAVAILABLE,
}

internal object IntegrityPolicy {

    /** Length verdict for a completed stream file. */
    fun verifySize(expectedBytes: Long?, actualBytes: Long): IntegrityVerdict = when {
        expectedBytes == null || expectedBytes <= 0L -> IntegrityVerdict.MISSING_SIZE
        actualBytes == expectedBytes -> IntegrityVerdict.OK
        else -> IntegrityVerdict.SIZE_MISMATCH
    }

    /** Parses a `Content-MD5` header; returns null when absent, malformed, or not 16 bytes. */
    fun parseContentMd5(header: String?): ByteArray? {
        if (header.isNullOrBlank()) return null
        val decoded = runCatching { Base64.getDecoder().decode(header.trim()) }.getOrNull()
        return decoded?.takeIf { it.size == 16 }
    }

    /** Computes the MD5 digest of [bytes] (JVM `MessageDigest`; no Android dependency). */
    fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

    /** Verifies raw received bytes against a `Content-MD5` header. */
    fun verifyContentMd5(header: String?, actualBytes: ByteArray): IntegrityVerdict {
        val expected = parseContentMd5(header) ?: return IntegrityVerdict.CHECKSUM_UNAVAILABLE
        val actual = md5(actualBytes)
        return if (expected.contentEquals(actual)) IntegrityVerdict.OK
        else IntegrityVerdict.CHECKSUM_MISMATCH
    }

    /** True when a verdict means the file must be rejected rather than finalized. */
    fun isFailure(verdict: IntegrityVerdict): Boolean =
        verdict == IntegrityVerdict.SIZE_MISMATCH || verdict == IntegrityVerdict.CHECKSUM_MISMATCH
}
