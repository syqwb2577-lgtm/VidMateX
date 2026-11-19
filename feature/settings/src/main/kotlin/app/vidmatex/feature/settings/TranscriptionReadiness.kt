package app.vidmatex.feature.settings

/**
 * Pure (Android-free) transcription-config completeness policy (§10 / §5): before the
 * Whisper transcription entry may be enabled, the endpoint must be present and accepted
 * by the probe validator. The API key is optional (keyless self-hosted servers work), so
 * its absence is a WARNING, never a blocking issue.
 *
 * The validator is injected as a function so this object stays independent of
 * `WhisperProbeUrlValidator` / `BackendUrlValidator` and remains JVM-unit-testable.
 */
enum class ReadinessIssue(val blocking: Boolean) {
    /** Endpoint field blank. */
    ENDPOINT_MISSING(blocking = true),
    /** Endpoint present but rejected by the URL policy (scheme/host rules). */
    ENDPOINT_INVALID(blocking = true),
    /** No API key: allowed for keyless self-hosted servers; hosted providers may 401. */
    KEY_ABSENT(blocking = false),
}

data class TranscriptionReadiness(
    val issues: List<ReadinessIssue>,
) {
    /** True when no blocking issue exists (a warning-only state still enables probing). */
    val ready: Boolean get() = issues.none { it.blocking }
    val blockingIssues: List<ReadinessIssue> get() = issues.filter { it.blocking }
    val warnings: List<ReadinessIssue> get() = issues.filter { !it.blocking }

    companion object {
        fun evaluate(
            endpoint: String,
            keyPresent: Boolean,
            endpointIsValid: (String) -> Boolean,
        ): TranscriptionReadiness {
            val trimmed = endpoint.trim()
            val issues = mutableListOf<ReadinessIssue>()
            if (trimmed.isEmpty()) {
                issues += ReadinessIssue.ENDPOINT_MISSING
            } else if (!endpointIsValid(trimmed)) {
                issues += ReadinessIssue.ENDPOINT_INVALID
            }
            if (!keyPresent) {
                issues += ReadinessIssue.KEY_ABSENT
            }
            return TranscriptionReadiness(issues)
        }

        /** Empty-config default: not ready. */
        val defaultNotReady: TranscriptionReadiness =
            TranscriptionReadiness(listOf(ReadinessIssue.ENDPOINT_MISSING))
    }
}
