package app.vidmatex.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable "download completed, transcription not yet done" row (migration 4→5).
 *
 * The [CompletedDownloadBus] is an in-process, replay=0 bus: if the app process dies
 * between `dao.markCompleted` and transcription enqueue, the completed download is lost
 * and never transcribed. This row is written atomically with completion (insert-or-ignore
 * by [dedupKey]) inside the same worker transaction boundary as `markCompleted`, so it
 * survives process death. On next app start [app.vidmatex.transcription.AutoTranscribeObserver]
 * scans `status = PENDING` rows and enqueues transcription exactly once.
 *
 * Idempotency (same file never transcribed twice) is guaranteed by three layers:
 *  1. UNIQUE index on [dedupKey] — `insertPendingIfAbsent` is INSERT OR IGNORE, so a
 *     second completion of the same file never creates a second row.
 *  2. WorkManager `enqueueUniqueWork("transcribe_$dedupKey", KEEP)` — even if the observer
 *     runs twice (scan + live event, or two app starts), only one Worker job exists.
 *  3. Terminal status transitions (SUCCEEDED/FAILED) are keyed by [dedupKey]; a row that
 *     reached SUCCEEDED is never re-enqueued (findPending filters status != PENDING).
 *
 * [dedupKey] is the SHA-256 hex of the normalized stored file path (see
 * [PendingTranscriptionKeys.keyFor]), computed identically to
 * `app.vidmatex.feature.subtitles.transcription.TranscriptionDedup.keyFor` so the worker
 * insert and the observer enqueue agree on the key. The two implementations are pure
 * string hashing and must stay in sync; the equality is asserted by
 * `PendingTranscriptionKeysTest`.
 */
@Entity(
    tableName = "pending_transcriptions",
    indices = [
        Index(value = ["dedupKey"], unique = true),
        Index("status"),
        Index("createdAtMs"),
    ],
)
data class PendingTranscriptionEntity(
    @PrimaryKey val id: String,
    /** Owning download row id (DownloadEntity.id); informational, not unique. */
    val downloadId: String,
    /** Final stored path/URI (absolute path, file://, or content://) as on DownloadEntity. */
    val filePath: String,
    /** Separate subtitle sidecar path; null when embedded or not requested. */
    val subtitlePath: String? = null,
    val title: String,
    val sourceUrl: String,
    /** Stable hash of normalized [filePath]; UNIQUE — the idempotency anchor. */
    val dedupKey: String,
    /** One of [PendingTranscriptionStatus]. SQL default 'PENDING' mirrors the Kotlin default. */
    @ColumnInfo(defaultValue = "'PENDING'") val status: String = PendingTranscriptionStatus.PENDING,
    /** Number of times the observer has enqueued a job for this row (incremented on ENQUEUED). */
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    // SQL defaults are 0 (used only by raw-SQL inserts); the Kotlin constructor default is
    // currentTimeMillis() so DAO inserts always carry a real timestamp. Room schema
    // validation requires the @ColumnInfo default to match MIGRATION_4_5 exactly.
    @ColumnInfo(defaultValue = "0") val createdAtMs: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = System.currentTimeMillis(),
    /** Populated on terminal FAILED; null otherwise. */
    val errorMessage: String? = null,
)

/**
 * Status values for [PendingTranscriptionEntity.status]. Pure string constants so the
 * state machine is JVM-testable without Room/Android.
 *
 * Legal transitions:
 *  PENDING   → ENQUEUED   (observer enqueued a WorkManager job)
 *  PENDING   → CANCELLED  (file not audio/video / path unresolvable — terminal, no retry)
 *  ENQUEUED  → SUCCEEDED  (TranscriptionWorker saved subtitles)
 *  ENQUEUED  → FAILED     (TranscriptionWorker retries exhausted / permanent failure)
 *  ENQUEUED  → PENDING    (worker cancelled / stopped — retryable on next app start)
 *  SUCCEEDED / FAILED / CANCELLED are terminal and never re-enqueued.
 */
object PendingTranscriptionStatus {
    const val PENDING = "PENDING"
    const val ENQUEUED = "ENQUEUED"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"

    private val TERMINAL = setOf(SUCCEEDED, FAILED, CANCELLED)

    /** True when the row will never be picked up by `findPending` again. */
    fun isTerminal(status: String): Boolean = status in TERMINAL

    /** True when `findPending` (status=PENDING) would return this row. */
    fun isRetryable(status: String): Boolean = status == PENDING
}

/**
 * Pure dedup-key computation, mirroring `TranscriptionDedup.keyFor` exactly. Lives in
 * `core/database` so workers (feature/downloads, which cannot depend on feature/subtitles)
 * can compute the same key the observer uses. Algorithm: normalize path separators and
 * collapse redundant slashes, then SHA-256 hex. Blank input yields a blank key (no dedup,
 * insert is skipped by the DAO).
 */
object PendingTranscriptionKeys {
    private const val ALGORITHM = "SHA-256"

    /** Must match `TranscriptionDedup.normalize` byte-for-byte. */
    fun normalize(path: String): String =
        path.trim().replace('\\', '/').replace(Regex("/+"), "/").trimEnd('/')

    /** Stable hex key; blank input returns "" (caller skips insert). */
    fun keyFor(path: String): String {
        val normalized = normalize(path)
        if (normalized.isBlank()) return ""
        val digest = java.security.MessageDigest.getInstance(ALGORITHM)
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
