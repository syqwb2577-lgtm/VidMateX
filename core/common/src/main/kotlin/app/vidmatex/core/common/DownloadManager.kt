package app.vidmatex.core.common

import app.vidmatex.core.model.DownloadItem
import app.vidmatex.core.model.DownloadSpec
import kotlinx.coroutines.flow.Flow

/**
 * Entry point for enqueuing and observing downloads. Implemented in :feature:downloads
 * (WorkManager + Room) and bound via Hilt. UI modules depend only on this interface.
 */
interface DownloadManager {
    fun observeAll(): Flow<List<DownloadItem>>
    fun observe(id: String): Flow<DownloadItem?>
    suspend fun enqueue(spec: DownloadSpec): AppResult<String>   // returns item id
    suspend fun pause(id: String)
    suspend fun resume(id: String)
    suspend fun cancel(id: String)
    suspend fun retry(id: String)
    suspend fun delete(id: String, deleteFile: Boolean = true)
    suspend fun clearHistory()
}
