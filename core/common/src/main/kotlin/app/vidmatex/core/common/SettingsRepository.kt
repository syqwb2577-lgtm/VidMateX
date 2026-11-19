package app.vidmatex.core.common

import app.vidmatex.core.model.AppSettings
import kotlinx.coroutines.flow.Flow

/** Implemented in :feature:settings (DataStore) and bound via Hilt. */
interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
    suspend fun current(): AppSettings
}
