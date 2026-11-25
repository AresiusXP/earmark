package xyz.aresius.audiobooks.core.download

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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import xyz.aresius.audiobooks.R

/** The ongoing notification of a running download (the foreground service of the worker). */
@Singleton
class DownloadNotifications @Inject constructor(@ApplicationContext private val context: Context) {

    fun foregroundInfo(asin: String, progress: LiveProgress?, preparing: Boolean): ForegroundInfo {
        val id = notificationId(asin)
        val notification = build(progress, preparing)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    fun build(progress: LiveProgress?, preparing: Boolean): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            context, 0,
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = progress?.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.downloads_notification_title)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (progress == null || preparing || progress.bytesTotal <= 0) {
            builder.setContentText(context.getString(R.string.downloads_notification_preparing)).setProgress(0, 0, true)
        } else {
            val percent = (progress.bytesDone * 100 / progress.bytesTotal).toInt().coerceIn(0, 100)
            builder.setContentText(context.getString(R.string.downloads_notification_progress, percent)).setProgress(100, percent, false)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.downloads_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "audiobooks_downloads"
        private const val BASE_ID = 4200

        fun notificationId(asin: String) = BASE_ID + (asin.hashCode() and 0xfff)
    }
}
