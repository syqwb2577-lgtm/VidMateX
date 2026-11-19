package app.vidmatex.feature.downloads

import java.util.UUID

/**
 * Deterministic WorkRequest IDs and unique-work names derived from the download item id.
 * `setId(...)` keeps `cancelWorkById` working; the stable unique names let
 * `enqueueUniqueWork(KEEP)` prevent duplicate scheduled work for the same item across
 * manager, receiver, and retry enqueues.
 */
internal object WorkIds {
    fun download(itemId: String): UUID =
        UUID.nameUUIDFromBytes("vidmatex:download:$itemId".toByteArray(Charsets.UTF_8))

    fun embed(itemId: String): UUID =
        UUID.nameUUIDFromBytes("vidmatex:embed:$itemId".toByteArray(Charsets.UTF_8))

    fun downloadWorkName(itemId: String): String = "vidmatex:download:$itemId"

    fun embedWorkName(itemId: String): String = "vidmatex:embed:$itemId"
}
