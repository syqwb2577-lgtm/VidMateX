package app.vidmatex.feature.downloads

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local record of whether each (itemId, track kind) accepted HTTP Range resumes.
 *
 * Persistence note: `core/database` `DownloadEntity` NOW HAS the `resumable` column
 * (Room v2 + `MIGRATION_1_2`); `DownloadWorker` calls `dao.updateResumable(...)` after
 * every probe/response so the verdict survives process death in the database. This map
 * remains the fast in-memory cache used by the failure path to decide whether partial
 * bytes may be kept. A DataStore JSON fallback map (`Constants.PREF_RESUME_MAP_JSON`,
 * key `"$itemId::$kind"`) is recorded as a follow-up in `_spec/CHANGES_REQUESTED.md`
 * because the downloads module has no `datastore-preferences` dependency yet. On any
 * cache miss the safe default is a fresh GET — never appending onto unverified bytes.
 */
internal object ResumeDecisionStore {
    private val resumable = ConcurrentHashMap<String, Boolean>()

    private fun key(itemId: String, kind: String) = "$itemId::$kind"

    fun mark(itemId: String, kind: String, allowed: Boolean) {
        resumable[key(itemId, kind)] = allowed
    }

    fun isResumable(itemId: String, kind: String): Boolean =
        resumable[key(itemId, kind)] == true

    fun clear(itemId: String) {
        resumable.keys.removeAll { it.startsWith("$itemId::") }
    }
}
