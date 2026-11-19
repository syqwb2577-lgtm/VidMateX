package app.vidmatex.feature.settings

import java.net.URI

/**
 * C5: reachability-probe URL rules for the Whisper endpoint, kept in sync with the
 * availability validator in `media/processor` (`WhisperEndpointValidator`):
 *  - `https` accepted for any host (managed/remote endpoints);
 *  - `http` accepted ONLY for loopback development hosts:
 *    `localhost`, `127.0.0.1`, `0.0.0.0`, `::1` / `[::1]`, `10.0.2.2` (emulator host
 *    machine), and names ending in `.localhost`;
 *  - every other scheme/host (including non-loopback `http://example.com`) rejected.
 *
 * The dev-loopback exception exists so a locally self-hosted Whisper server can be
 * probed during development; it never sends an API key (the probe attaches no key) and
 * production endpoints remain HTTPS-only. Pure JVM logic (`java.net.URI` only; no Android
 * framework calls) so it can be exercised by local unit tests.
 */
internal object WhisperProbeUrlValidator {
    private val LOOPBACK_HOSTS = setOf(
        "localhost",
        "127.0.0.1",
        "0.0.0.0",
        "::1",
        "[::1]",
        "10.0.2.2",
    )

    /** True when the endpoint may be probed by `testWhisperConnection()`. */
    fun isValidProbeUrl(raw: String?): Boolean {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        if (scheme.isEmpty() || host.isEmpty()) return false
        return when (scheme) {
            "https" -> true
            "http" -> host in LOOPBACK_HOSTS || host.endsWith(".localhost") || host.endsWith(".local")
            else -> false
        }
    }
}
