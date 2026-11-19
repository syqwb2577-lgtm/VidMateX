package app.vidmatex.player

import app.vidmatex.core.common.SecretTextRedactor

/**
 * Typed playback failure categories (§3). The UI maps [category] to a localized string;
 * the raw Media3 reason is redacted before it can reach any state/log so a URL with
 * credentials or a token never appears on screen or in logs.
 */
enum class PlayerErrorCategory {
    /** Reachability/DNS/TCP/HTTP transport failures (not a pure timeout). */
    NETWORK,

    /** Connect/read/streaming timeout. */
    TIMEOUT,

    /** Container/codec/format the device cannot parse or decode. */
    UNSUPPORTED_FORMAT,

    /** Missing storage permission or cleartext policy denial. */
    PERMISSION,

    /** Local file missing / unreadable. */
    FILE_MISSING,

    /** Anything that does not fit the categories above. */
    UNKNOWN,
}

data class PlayerError(
    val category: PlayerErrorCategory,
    /** Media3 `errorCodeName`, e.g. `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` (diagnostic only). */
    val codeName: String,
    /** Redacted, safe-to-display short reason; never contains secrets or credentials. */
    val reason: String,
)

/**
 * Pure mapper from Media3 `PlaybackException` fields to [PlayerError]. The controller
 * passes `errorCode`, `errorCodeName`, and `message`; this object owns the classification
 * so rules are JVM-testable without instantiating ExoPlayer.
 *
 * Code constants mirror `androidx.media3.common.PlaybackException` (media3 1.3.1) and are
 * redeclared here to keep this class free of framework imports.
 */
object PlayerErrorMapper {
    // --- Mirrored PlaybackException codes (media3 1.3.1) ---
    private const val CODE_TIMEOUT = 1003
    private const val CODE_IO_UNSPECIFIED = 2000
    private const val CODE_IO_NETWORK_CONNECTION_FAILED = 2001
    private const val CODE_IO_NETWORK_CONNECTION_TIMEOUT = 2002
    private const val CODE_IO_INVALID_HTTP_CONTENT_TYPE = 2003
    private const val CODE_IO_BAD_HTTP_STATUS = 2004
    private const val CODE_IO_FILE_NOT_FOUND = 2005
    private const val CODE_IO_NO_PERMISSION = 2006
    private const val CODE_IO_CLEARTEXT_NOT_PERMITTED = 2007
    private const val CODE_PARSING_CONTAINER_MALFORMED = 3000
    private const val CODE_PARSING_CONTAINER_UNSUPPORTED = 3001
    private const val CODE_PARSING_MANIFEST_MALFORMED = 3002
    private const val CODE_PARSING_MANIFEST_UNSUPPORTED = 3003
    private const val CODE_DECODER_INIT_FAILED = 4000
    private const val CODE_DECODING_FORMAT_UNSUPPORTED = 4002
    private const val CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES = 4003
    private const val CODE_DECODING_ERROR = 4004

    fun map(errorCode: Int, errorCodeName: String?, reason: String?): PlayerError {
        val name = errorCodeName.orEmpty()
        val safeReason = SecretTextRedactor.redact(reason.orEmpty().trim())
        val lowerReason = safeReason.lowercase()
        val lowerName = name.lowercase()

        val category = when {
            errorCode == CODE_TIMEOUT ||
                errorCode == CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                lowerName.contains("timeout") ||
                lowerReason.contains("timed out") ||
                lowerReason.contains("timeout") -> PlayerErrorCategory.TIMEOUT

            errorCode == CODE_IO_FILE_NOT_FOUND ||
                lowerReason.contains("no such file") ||
                lowerReason.contains("file not found") -> PlayerErrorCategory.FILE_MISSING

            errorCode == CODE_IO_NO_PERMISSION ||
                errorCode == CODE_IO_CLEARTEXT_NOT_PERMITTED ||
                lowerReason.contains("permission denied") ||
                lowerName.contains("cleartext") -> PlayerErrorCategory.PERMISSION

            errorCode in 2000..2007 -> PlayerErrorCategory.NETWORK // IO transport errors (2005/2006/2007 handled above)
            errorCode == CODE_PARSING_CONTAINER_UNSUPPORTED ||
                errorCode == CODE_PARSING_MANIFEST_UNSUPPORTED ||
                errorCode == CODE_DECODING_FORMAT_UNSUPPORTED ||
                errorCode == CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                errorCode == CODE_DECODER_INIT_FAILED ||
                errorCode == CODE_PARSING_CONTAINER_MALFORMED ||
                errorCode == CODE_PARSING_MANIFEST_MALFORMED ||
                lowerReason.contains("unsupported") ||
                lowerReason.contains("codec") ||
                lowerReason.contains("mime") -> PlayerErrorCategory.UNSUPPORTED_FORMAT

            errorCode == CODE_IO_NETWORK_CONNECTION_FAILED ||
                errorCode == CODE_IO_BAD_HTTP_STATUS ||
                errorCode == CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
                errorCode == CODE_IO_UNSPECIFIED ||
                lowerReason.contains("unable to resolve host") ||
                lowerReason.contains("connect") ||
                lowerReason.contains("network") -> PlayerErrorCategory.NETWORK

            else -> PlayerErrorCategory.UNKNOWN
        }
        return PlayerError(
            category = category,
            codeName = name.ifBlank { "ERROR_CODE_$errorCode" },
            reason = safeReason,
        )
    }
}
