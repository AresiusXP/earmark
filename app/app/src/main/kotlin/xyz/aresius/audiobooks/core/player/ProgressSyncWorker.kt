package xyz.aresius.audiobooks.core.player

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import xyz.aresius.audiobooks.core.auth.SessionStore

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProgressSyncEntryPoint {
    fun progressSync(): ProgressSync
    fun bookmarks(): BookmarkRepository
    fun durations(): DurationReporter
    fun sessions(): SessionStore
}

/** Sends the progress, bookmarks and learned durations that could not reach the server, once there is a network. */
class ProgressSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, ProgressSyncEntryPoint::class.java)
        val userId = entry.sessions().load()?.userId ?: return Result.success()
        entry.bookmarks().sync(null)
        val durationsDone = entry.durations().flush()
        return if (entry.progressSync().flush(userId) && durationsDone) Result.success() else Result.retry()
    }
}

class WorkSyncScheduler(private val context: Context) : SyncScheduler {
    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<ProgressSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "audiobooks-progress-sync"
    }
}
