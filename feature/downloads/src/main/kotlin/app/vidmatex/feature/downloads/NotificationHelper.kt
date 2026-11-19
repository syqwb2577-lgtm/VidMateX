package app.vidmatex.feature.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import app.vidmatex.core.common.Constants
import app.vidmatex.feature.downloads.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val nm: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannels()
    }

    fun notificationId(itemId: String): Int =
        Constants.NOTIFICATION_ID_BASE + (itemId.hashCode() and 0x7FFFFFFF)

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val downloads = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_DOWNLOADS,
            context.getString(R.string.dl_channel_downloads_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.dl_channel_downloads_desc); setShowBadge(false) }
        val processing = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_PROCESSING,
            context.getString(R.string.dl_channel_processing_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.dl_channel_processing_desc); setShowBadge(false) }
        nm.createNotificationChannels(listOf(downloads, processing))
    }

    private fun launchContentIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun progressNotification(
        itemId: String,
        title: String,
        percent: Int,
        speedBps: Long,
        etaSec: Long?,
        indeterminate: Boolean,
    ): Notification {
        val text = buildString {
            append(if (indeterminate) context.getString(R.string.dl_notif_downloading) else "$percent%")
            if (speedBps > 0) append(" · ${FormatUtils.formatSpeed(speedBps)}")
            if (!indeterminate && etaSec != null && etaSec > 0) append(" · ${context.getString(R.string.dl_notif_eta_left, FormatUtils.formatEta(etaSec))}")
        }
        return NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title.ifBlank { context.getString(R.string.dl_notif_title_downloading) })
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .setContentIntent(launchContentIntent())
            .addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.dl_notif_action_pause),
                DownloadActionReceiver.pausePendingIntent(context, itemId),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.dl_notif_action_cancel),
                DownloadActionReceiver.cancelPendingIntent(context, itemId),
            )
            .build()
    }

    fun processingNotification(itemId: String, title: String): Notification =
        NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_PROCESSING)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title.ifBlank { context.getString(R.string.dl_notif_title_processing) })
            .setContentText(context.getString(R.string.dl_notif_embedding))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setContentIntent(launchContentIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.dl_notif_action_cancel),
                DownloadActionReceiver.cancelPendingIntent(context, itemId),
            )
            .build()

    fun foregroundInfo(
        itemId: String,
        title: String,
        percent: Int = 0,
        speedBps: Long = 0,
        etaSec: Long? = null,
        indeterminate: Boolean = true,
        processing: Boolean = false,
    ): ForegroundInfo {
        val n = if (processing) processingNotification(itemId, title)
        else progressNotification(itemId, title, percent, speedBps, etaSec, indeterminate)
        val id = notificationId(itemId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, n)
        }
    }

    fun notifyProgress(itemId: String, title: String, percent: Int, speedBps: Long, etaSec: Long?, indeterminate: Boolean) {
        nm.notify(notificationId(itemId), progressNotification(itemId, title, percent, speedBps, etaSec, indeterminate))
    }

    fun notifyProcessing(itemId: String, title: String) {
        nm.notify(notificationId(itemId), processingNotification(itemId, title))
    }

    fun notifyCompleted(itemId: String, title: String) {
        val n = NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title.ifBlank { context.getString(R.string.dl_notif_complete) })
            .setContentText(context.getString(R.string.dl_notif_complete_text))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(launchContentIntent())
            .build()
        nm.notify(notificationId(itemId), n)
    }

    fun notifyFailed(itemId: String, title: String, error: String) {
        val n = NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title.ifBlank { context.getString(R.string.dl_notif_failed) })
            .setContentText(error.take(80))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(launchContentIntent())
            .build()
        nm.notify(notificationId(itemId), n)
    }

    /** Paused-state notification with resume/cancel actions wired to [DownloadActionReceiver]. */
    fun notifyPaused(itemId: String, title: String, percent: Int) {
        val text = if (percent > 0) {
            context.getString(R.string.dl_notif_paused_at, percent)
        } else {
            context.getString(R.string.dl_notif_paused)
        }
        val n = NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle(title.ifBlank { context.getString(R.string.dl_notif_paused) })
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(launchContentIntent())
            .addAction(
                android.R.drawable.ic_media_play,
                context.getString(R.string.dl_cd_resume),
                DownloadActionReceiver.resumePendingIntent(context, itemId),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.dl_notif_action_cancel),
                DownloadActionReceiver.cancelPendingIntent(context, itemId),
            )
            .build()
        nm.notify(notificationId(itemId), n)
    }

    fun cancel(itemId: String) {
        nm.cancel(notificationId(itemId))
    }
}
