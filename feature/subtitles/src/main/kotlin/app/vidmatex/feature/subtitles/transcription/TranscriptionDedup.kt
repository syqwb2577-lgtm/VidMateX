package app.vidmatex.feature.subtitles.transcription

import java.security.MessageDigest

/**
 * Deduplication for transcription jobs: one active job per local source file.
 *
 * The dedup key is a SHA-256 hex digest of the normalized absolute path (the file
 * hash itself would require reading bytes and is unstable while a download is still
 * being written; the path identifies the source slot). The worker additionally
 * carries the key in input/output data so the ViewModel registry can prune it on any
 * terminal state. Pure JVM logic (`java.security.MessageDigest`); no Android imports.
 */
object TranscriptionDedup {
    private const val ALGORITHM = "SHA-256"

    /** Normalize separators/whitespace before hashing so equivalent paths collide. */
    fun normalize(path: String): String =
        path.trim().replace('\\', '/').replace(Regex("/+"), "/").trimEnd('/')

    /** Stable hex key for a local file path; blank input yields a blank key (no dedup). */
    fun keyFor(path: String): String {
        val normalized = normalize(path)
        if (normalized.isBlank()) return ""
        val digest = MessageDigest.getInstance(ALGORITHM).digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** True when two non-blank paths identify the same dedup slot. */
    fun sameSource(a: String, b: String): Boolean {
        val ka = keyFor(a); val kb = keyFor(b)
        return ka.isNotBlank() && ka == kb
    }
}

/**
 * Process-local registry of active dedup keys. The ViewModel owns one instance; the
 * worker data key lets the registry prune entries after SUCCESS/FAILED/CANCELLED.
 * Backed by a synchronized set for use from coroutine dispatchers.
 */
class ActiveTranscriptionSet {
    private val keys = mutableSetOf<String>()

    /** @return true when the key was added; false when it was already active. */
    fun tryAdd(key: String): Boolean = synchronized(keys) {
        if (key.isBlank()) return true
        keys.add(key)
    }

    fun remove(key: String) = synchronized(keys) { keys.remove(key) }

    fun contains(key: String): Boolean = synchronized(keys) { keys.contains(key) }

    fun clear() = synchronized(keys) { keys.clear() }

    val activeCount: Int get() = synchronized(keys) { keys.size }
}
