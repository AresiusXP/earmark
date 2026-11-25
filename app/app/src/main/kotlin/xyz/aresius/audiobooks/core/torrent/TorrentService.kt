package xyz.aresius.audiobooks.core.torrent

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.MainActivity
import xyz.aresius.audiobooks.R

/**
 * Foreground service (type dataSync) that keeps the process alive while torrents run. It exists only while the engine is busy
 * (the engine starts it, it stops itself a few seconds after the engine went idle). The notification shows speed, peers and ETA
 * with Pause / Stop actions. Android 15 caps dataSync services at 6 h per 24 h: [onTimeout] pauses everything, tells the user and
 * schedules [TorrentResumeWorker]. `specialUse` is not used.
 */
@AndroidEntryPoint
class TorrentService : Service() {
    @Inject lateinit var runtime: TorrentRuntime

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TorrentNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, TorrentNotifications.ONGOING_ID, TorrentNotifications.ongoing(this, runtime.activity.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            // Android 15: the dataSync budget (6 h / 24 h) is used up, or the app may not start a foreground service from the background.
            // Everything pauses (Paused(SYSTEM)) and the resume worker tries again; nothing keeps running unprotected.
            runtime.suspendForForegroundRefusal()
            TorrentNotifications.notifyTimeout(this)
            TorrentResumeWorker.schedule(this)
            shutDown()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE -> runtime.setUserPaused(true)
            ACTION_RESUME -> runtime.setUserPaused(false)
            ACTION_STOP -> {
                runtime.stopAll()
                shutDown()
                return START_NOT_STICKY
            }
        }
        if (observer == null) observer = scope.launch { observe() }
        return START_NOT_STICKY
    }

    private suspend fun observe() {
        var idleSince = 0L
        runtime.activity.collectLatest { a ->
            if (a.idle) {
                if (idleSince == 0L) idleSince = System.currentTimeMillis()
                releaseWakeLock()
                delay(IDLE_GRACE_MS)
                if (runtime.activity.value.idle) shutDown()
                return@collectLatest
            }
            idleSince = 0L
            NotificationManagerCompat.from(this).notifyIfAllowed(this, TorrentNotifications.ONGOING_ID, TorrentNotifications.ongoing(this, a))
            if (a.paused == null) holdWakeLock() else releaseWakeLock()
            delay(1_000)
        }
    }

    // Held only while data is flowing, with a timeout that the next activity update renews, so a crash cannot leave it on.
    private fun holdWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audiobooks:torrent").also { it.setReferenceCounted(false); wakeLock = it }
        lock.acquire(WAKE_LOCK_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun shutDown() {
        observer?.cancel(); observer = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 14 (any dataSync service past its limit) and 15+ call one of these; both must end with the service stopped. */
    override fun onTimeout(startId: Int) = handleTimeout()

    override fun onTimeout(startId: Int, fgsType: Int) = handleTimeout()

    private fun handleTimeout() {
        runtime.suspendForTimeLimit()
        TorrentNotifications.notifyTimeout(this)
        TorrentResumeWorker.schedule(this)
        shutDown()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PAUSE = "xyz.aresius.audiobooks.torrent.PAUSE"
        const val ACTION_RESUME = "xyz.aresius.audiobooks.torrent.RESUME"
        const val ACTION_STOP = "xyz.aresius.audiobooks.torrent.STOP"
        private const val IDLE_GRACE_MS = 5_000L
        private const val WAKE_LOCK_MS = 3 * 60_000L
    }
}

@SuppressLint("MissingPermission")
private fun NotificationManagerCompat.notifyIfAllowed(context: Context, id: Int, n: Notification) {
    if (areNotificationsEnabled()) notify(id, n)
}

internal object TorrentNotifications {
    const val CHANNEL = "torrent"
    const val ONGOING_ID = 4101
    const val TIMEOUT_ID = 4102

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.torrent_channel_name), NotificationManager.IMPORTANCE_LOW)
            .apply { description = context.getString(R.string.torrent_channel_desc); setShowBadge(false) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(context: Context, label: Int, action: String): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            context, action.hashCode(), Intent(context, TorrentService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, context.getString(label), pi).build()
    }

    fun ongoing(context: Context, a: EngineActivity): Notification {
        val text = when (a.paused) {
            PauseReason.VPN_REQUIRED -> context.getString(R.string.torrent_notif_paused_vpn)
            PauseReason.WIFI_REQUIRED -> context.getString(R.string.torrent_notif_paused_wifi)
            PauseReason.LOW_BATTERY -> context.getString(R.string.torrent_notif_paused_battery)
            PauseReason.USER -> context.getString(R.string.torrent_notif_paused_user)
            PauseReason.SYSTEM -> context.getString(R.string.torrent_notif_paused_system)
            null -> when {
                a.downloads == 0 && a.seeding > 0 -> context.getString(R.string.torrent_notif_seeding)
                a.bytesTotal == 0L -> context.getString(R.string.torrent_notif_connecting)
                else -> context.getString(
                    R.string.torrent_notif_progress,
                    Formatter.formatFileSize(context, a.bytesDone), Formatter.formatFileSize(context, a.bytesTotal),
                    Formatter.formatFileSize(context, a.downBytesPerSec), a.peers,
                )
            }
        }
        val eta = a.etaSeconds?.takeIf { a.paused == null }?.let { context.getString(R.string.torrent_notif_eta, formatEta(it)) }
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.torrent_notif_title))
            .setContentText(text)
            .setSubText(eta)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (a.bytesTotal > 0) b.setProgress(1000, (a.bytesDone * 1000 / a.bytesTotal).toInt().coerceIn(0, 1000), false)
        if (a.paused == null) b.addAction(action(context, R.string.torrent_action_pause, TorrentService.ACTION_PAUSE))
        else b.addAction(action(context, R.string.torrent_action_resume, TorrentService.ACTION_RESUME))
        b.addAction(action(context, R.string.torrent_action_stop, TorrentService.ACTION_STOP))
        return b.build()
    }

    fun notifyTimeout(context: Context) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.torrent_timeout_title))
            .setContentText(context.getString(R.string.torrent_timeout_text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notifyIfAllowed(context, TIMEOUT_ID, n)
    }

    internal fun formatEta(seconds: Long): String = when {
        seconds >= 3600 -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
        seconds >= 60 -> "${seconds / 60} min"
        else -> "$seconds s"
    }
}

/**
 * Starts [TorrentService]. A refusal (Android 12+ background-start limits) is rethrown after the user was told and [TorrentResumeWorker]
 * was scheduled: the engine pauses everything ([PauseReason.SYSTEM]) instead of running without the foreground protection.
 */
class AndroidServiceLauncher @Inject constructor(@ApplicationContext private val context: Context) : ServiceLauncher {
    override fun ensureRunning() {
        try {
            context.startForegroundService(Intent(context, TorrentService::class.java))
        } catch (e: Exception) {
            TorrentNotifications.notifyTimeout(context)
            TorrentResumeWorker.schedule(context)
            throw e
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TorrentEntryPoint {
    fun runtime(): TorrentRuntime

    fun engine(): TorrentEngine

    fun settings(): TorrentSettingsStore
}

/**
 * After the system's dataSync time limit the downloads stay paused. This job tries again later (network connected, battery not
 * low): it starts the service in the foreground-allowed way and resumes. When Android still refuses it retries with back-off, and
 * the user can always resume by opening the app (the engine also resumes when the app comes to the foreground).
 */
class TorrentResumeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runtime = EntryPointAccessors.fromApplication(applicationContext, TorrentEntryPoint::class.java).runtime()
        if (!runtime.suspendedForTimeLimit) return Result.success()
        return try {
            applicationContext.startForegroundService(Intent(applicationContext, TorrentService::class.java).setAction(TorrentService.ACTION_RESUME))
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "torrent-resume"

        fun schedule(context: Context, delayMinutes: Long = 60) {
            val request = OneTimeWorkRequestBuilder<TorrentResumeWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
