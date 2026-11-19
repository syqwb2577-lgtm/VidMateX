package app.vidmatex.feature.downloads

/**
 * Pure worker input-data contract (no Android/WorkManager imports; JVM-testable).
 *
 * A download worker run requires both the row id and the serialized [app.vidmatex.core.model.DownloadSpec].
 * Missing/blank inputs mean a stale or corrupted enqueue: the worker must fail permanently
 * (`Result.failure()`) rather than download a guessed URL or post fake progress.
 */
internal object WorkerInputPolicy {

    data class Inputs(val itemId: String, val specJson: String)

    fun parse(itemId: String?, specJson: String?): Inputs? {
        val id = itemId?.takeIf { it.isNotBlank() } ?: return null
        val json = specJson?.takeIf { it.isNotBlank() } ?: return null
        return Inputs(id, json)
    }

    /** Minimum shape check for the spec payload before JSON parsing (non-blank, JSON-ish). */
    fun specPayloadLooksValid(json: String): Boolean {
        val trimmed = json.trim()
        return trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed.length > 4
    }
}
