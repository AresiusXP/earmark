package xyz.aresius.audiobooks.core.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Starts and stops the background work of the downloads. The real one is WorkManager; tests use a recording fake. */
interface DownloadScheduler {
    /** [replace] restarts a running worker (constraints changed); otherwise a worker that already exists is left alone. */
    fun enqueue(userId: String, asin: String, unmeteredOnly: Boolean, allowMobileData: Boolean, replace: Boolean, expedited: Boolean = true)

    fun cancel(userId: String, asin: String)

    fun cancelAll()

    fun ensureMaintenanceScheduled()
}

object DownloadWork {
    const val TAG = "audiobooks-download"
    const val KEY_USER = "user"
    const val KEY_ASIN = "asin"
    const val MAINTENANCE_NAME = "audiobooks-download-maintenance"
    const val BACKOFF_SECONDS = 30L

    fun uniqueName(userId: String, asin: String) = "download:$userId:$asin"

    /** Wi-Fi (unmetered) unless the user allows mobile data and this book is not forced to Wi-Fi. */
    fun networkType(unmeteredOnly: Boolean, allowMobileData: Boolean): NetworkType =
        if (unmeteredOnly || !allowMobileData) NetworkType.UNMETERED else NetworkType.CONNECTED

    fun input(userId: String, asin: String): Data = Data.Builder().putString(KEY_USER, userId).putString(KEY_ASIN, asin).build()
}

@Singleton
class WorkManagerScheduler @Inject constructor(@ApplicationContext private val context: Context) : DownloadScheduler {
    private val work get() = WorkManager.getInstance(context)

    override fun enqueue(userId: String, asin: String, unmeteredOnly: Boolean, allowMobileData: Boolean, replace: Boolean, expedited: Boolean) {
        val request = OneTimeWorkRequestBuilder<BookDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(DownloadWork.networkType(unmeteredOnly, allowMobileData)).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, DownloadWork.BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(DownloadWork.input(userId, asin))
            .addTag(DownloadWork.TAG)
            .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        work.enqueueUniqueWork(DownloadWork.uniqueName(userId, asin), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(userId: String, asin: String) {
        work.cancelUniqueWork(DownloadWork.uniqueName(userId, asin))
    }

    override fun cancelAll() {
        work.cancelAllWorkByTag(DownloadWork.TAG)
    }

    override fun ensureMaintenanceScheduled() {
        val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        work.enqueueUniquePeriodicWork(DownloadWork.MAINTENANCE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
