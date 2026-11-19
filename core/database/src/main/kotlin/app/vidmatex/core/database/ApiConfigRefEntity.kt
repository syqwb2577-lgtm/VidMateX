package app.vidmatex.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Non-secret reference to a configured API endpoint (additive table, migration 2→3).
 *
 * HARD SECURITY RULE: this row stores ONLY the provider alias, endpoint/base URL, auth
 * mode label, and timestamps. It MUST NOT contain an API key, token, or secret value.
 * [keyAlias] references the Keystore-encrypted secrets store entry
 * ([app.vidmatex.core.model.SecretKeyRef.alias]); the plaintext key is resolved at call
 * time by the settings/security layer and never persisted here.
 */
@Entity(tableName = "api_config_refs")
data class ApiConfigRefEntity(
    /** Stable row key, e.g. "resolver" | "whisper" | "libre_translate". */
    @PrimaryKey val alias: String,
    /** Provider label, e.g. "resolver" | "whisper" | "libre_translate". */
    val provider: String,
    /** Public https base URL; blank when unconfigured. No credentials in this field. */
    @ColumnInfo(defaultValue = "''") val baseUrl: String = "",
    /** Optional API path prefix; never a secret. */
    @ColumnInfo(defaultValue = "''") val endpointPath: String = "",
    /** "none" | "header_key" | "bearer" — transport label only, no token. */
    @ColumnInfo(defaultValue = "'none'") val authMode: String = "none",
    /** Alias into the encrypted secrets store; empty when authMode = "none". */
    @ColumnInfo(defaultValue = "''") val keyAlias: String = "",
    @ColumnInfo(defaultValue = "0") val createdAtMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val lastTestedAtMs: Long = 0L,
    /** Last reachability verdict, e.g. "ok" | "unauthorized" | "unreachable". */
    @ColumnInfo(defaultValue = "''") val lastStatus: String = "",
)
