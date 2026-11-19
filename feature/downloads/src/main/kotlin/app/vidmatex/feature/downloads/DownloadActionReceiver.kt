package app.vidmatex.feature.downloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.vidmatex.core.common.Constants
import app.vidmatex.core.common.SettingsRepository
import app.vidmatex.core.database.DownloadDao
import app.vidmatex.core.model.DownloadState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles notification actions:
 *  - [ACTION_CANCEL]: cancel download + embed workers, mark CANCELLED, remove temps.
 *  - [ACTION_PAUSE]: cancel workers but keep partial bytes; mark PAUSED and post the
 *    paused notification (resume works when the server honors HTTP Range).
 *  - [ACTION_RESUME]: re-enqueue the download worker with the persisted spec JSON.
 * Uses goAsync() so the DB write completes before the receiver returns.
 */
@AndroidEntryPoint
class DownloadActionReceiver : BroadcastReceiver() {
    @Inject lateinit var downloadDao: DownloadDao
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return
        val wm = WorkManager.getInstance(context)
        when (intent.action) {
            ACTION_CANCEL -> {
                wm.cancelWorkById(WorkIds.download(itemId))
                wm.cancelWorkById(WorkIds.embed(itemId))
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        downloadDao.updateState(itemId, DownloadState.CANCELLED.name)
                        FileStorageHelper.cleanTempFiles(context, itemId)
                    } catch (_: Throwable) {
                        // best-effort; the worker will also observe cancellation
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_PAUSE -> {
                wm.cancelWorkById(WorkIds.download(itemId))
                wm.cancelWorkById(WorkIds.embed(itemId))
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        downloadDao.updateState(itemId, DownloadState.PAUSED.name)
                        val entity = downloadDao.getById(itemId)
                        notificationHelper.notifyPaused(
                            itemId,
                            entity?.title.orEmpty(),
                            entity?.progressPercent ?: 0,
                        )
                    } catch (_: Throwable) {
                        // best-effort notification update
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_RESUME -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val entity = downloadDao.getById(itemId)
                        if (entity != null && entity.state == DownloadState.PAUSED) {
                            downloadDao.updateState(itemId, DownloadState.QUEUED.name)
                            val requireWifi = settingsRepository.current().requireWifi
                            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                                .setId(WorkIds.download(itemId))
                                .addTag(Constants.WORK_TAG_DOWNLOAD)
                                .setConstraints(DownloadWorkConstraints.build(requireWifi))
                                .setInputData(
                                    workDataOf(
                                        Constants.KEY_DOWNLOAD_ITEM_ID to itemId,
                                        Constants.KEY_DOWNLOAD_SPEC to entity.specJson,
                                    )
                                )
                                .build()
                            wm.enqueueUniqueWork(
                                WorkIds.downloadWorkName(itemId),
                                ExistingWorkPolicy.KEEP,
                                request,
                            )
                        }
                    } catch (_: Throwable) {
                        // best-effort; user can resume from the Downloads screen
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> return
        }
    }

    companion object {
        const val ACTION_CANCEL = "app.vidmatex.feature.downloads.ACTION_CANCEL"
        const val ACTION_PAUSE = "app.vidmatex.feature.downloads.ACTION_PAUSE"
        const val ACTION_RESUME = "app.vidmatex.feature.downloads.ACTION_RESUME"
        const val EXTRA_ITEM_ID = "item_id"

        fun cancelPendingIntent(context: Context, itemId: String): PendingIntent {
            val intent = Intent(context, DownloadActionReceiver::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_ITEM_ID, itemId)
            return PendingIntent.getBroadcast(
                context,
                itemId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun pausePendingIntent(context: Context, itemId: String): PendingIntent {
            val intent = Intent(context, DownloadActionReceiver::class.java)
                .setAction(ACTION_PAUSE)
                .putExtra(EXTRA_ITEM_ID, itemId)
            return PendingIntent.getBroadcast(
                context,
                itemId.hashCode() + 1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun resumePendingIntent(context: Context, itemId: String): PendingIntent {
            val intent = Intent(context, DownloadActionReceiver::class.java)
                .setAction(ACTION_RESUME)
                .putExtra(EXTRA_ITEM_ID, itemId)
            return PendingIntent.getBroadcast(
                context,
                itemId.hashCode() + 2,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
