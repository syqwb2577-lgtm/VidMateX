package app.vidmatex.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * JVM-pure tests for [PlayerErrorMapper]: Media3 error codes/reason strings classify into
 * the typed user-facing categories, and embedded secrets/credentials are redacted.
 */
class PlayerErrorMapperTest {

    @Test
    fun networkConnectionFailure_mapsToNetwork() {
        val error = PlayerErrorMapper.map(2001, "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", "Failed to connect")
        assertEquals(PlayerErrorCategory.NETWORK, error.category)
    }

    @Test
    fun httpStatusAndContentTypeFailures_mapToNetwork() {
        assertEquals(
            PlayerErrorCategory.NETWORK,
            PlayerErrorMapper.map(2004, "ERROR_CODE_IO_BAD_HTTP_STATUS", "HTTP 404").category,
        )
        assertEquals(
            PlayerErrorCategory.NETWORK,
            PlayerErrorMapper.map(2003, "ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE", "bad mime").category,
        )
    }

    @Test
    fun timeoutCodeAndTimeoutReason_mapToTimeout() {
        assertEquals(
            PlayerErrorCategory.TIMEOUT,
            PlayerErrorMapper.map(1003, "ERROR_CODE_TIMEOUT", "request timed out").category,
        )
        assertEquals(
            PlayerErrorCategory.TIMEOUT,
            PlayerErrorMapper.map(2002, "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT", "SocketTimeoutException").category,
        )
        assertEquals(
            PlayerErrorCategory.TIMEOUT,
            PlayerErrorMapper.map(9999, "OTHER", "connect timed out").category,
        )
    }

    @Test
    fun unsupportedContainerCodec_mapsToUnsupportedFormat() {
        assertEquals(
            PlayerErrorCategory.UNSUPPORTED_FORMAT,
            PlayerErrorMapper.map(3001, "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED", "container").category,
        )
        assertEquals(
            PlayerErrorCategory.UNSUPPORTED_FORMAT,
            PlayerErrorMapper.map(4002, "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED", "codec unsupported").category,
        )
        assertEquals(
            PlayerErrorCategory.UNSUPPORTED_FORMAT,
            PlayerErrorMapper.map(9999, "X", "UnsupportedMimeTypeException").category,
        )
    }

    @Test
    fun missingFile_mapsToFileMissing() {
        assertEquals(
            PlayerErrorCategory.FILE_MISSING,
            PlayerErrorMapper.map(2005, "ERROR_CODE_IO_FILE_NOT_FOUND", "no such file").category,
        )
    }

    @Test
    fun permissionAndCleartext_mapsToPermission() {
        assertEquals(
            PlayerErrorCategory.PERMISSION,
            PlayerErrorMapper.map(2006, "ERROR_CODE_IO_NO_PERMISSION", "permission denied").category,
        )
        assertEquals(
            PlayerErrorCategory.PERMISSION,
            PlayerErrorMapper.map(2007, "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED", "cleartext").category,
        )
    }

    @Test
    fun unknownCodeFallsBackToUnknown() {
        assertEquals(
            PlayerErrorCategory.UNKNOWN,
            PlayerErrorMapper.map(9999, "ERROR_CODE_UNSPECIFIED", "something broke").category,
        )
    }

    @Test
    fun reasonNeverEchoesEmbeddedSecret() {
        val error = PlayerErrorMapper.map(
            2001,
            "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            "GET https://host/api?api_key=supersecret123 failed",
        )
        assertFalse(error.reason.contains("supersecret123"))
        assertFalse(error.reason.contains("api_key=supersecret"))
    }

    @Test
    fun blankReason_keepsDiagnosticCodeName() {
        val error = PlayerErrorMapper.map(3001, "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED", null)
        assertEquals("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED", error.codeName)
        assertEquals(PlayerErrorCategory.UNSUPPORTED_FORMAT, error.category)
    }
}
