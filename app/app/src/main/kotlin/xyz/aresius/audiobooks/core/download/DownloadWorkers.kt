package xyz.aresius.audiobooks.core.download

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** What the workers need. Hilt provides it (through [DownloadWorkerEntryPoint]); tests install their own with [DownloadWorkerDeps.override]. */
interface DownloadWorkerDeps {
    val runner: DownloadRunner
    val bus: ProgressBus
    val notifications: DownloadNotifications
    val scheduler: DownloadScheduler
    val maintenance: DownloadMaintenance

    companion object {
        @Volatile
        var override: DownloadWorkerDeps? = null

        fun from(context: Context): DownloadWorkerDeps = override
            ?: EntryPointAccessors.fromApplication(context.applicationContext, DownloadWorkerEntryPoint::class.java).deps()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadWorkerEntryPoint {
    fun deps(): DownloadWorkerDeps
}

/**
 * One book. Expedited with a foreground notification (dataSync) so a long download survives the app going away; on Android 15 the
 * system stops a dataSync service after about 6 h a day: the worker then re-enqueues itself (not expedited) (the downloads resume with Range).
 * Results: Done -> success; Retry/Waiting -> retry (exponential backoff, WorkManager constraints decide when); Failed/Gone -> failure.
 */
class BookDownloadWorker(
    context: Context,
    params: WorkerParameters,
    private val deps: DownloadWorkerDeps,
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, DownloadWorkerDeps.from(context))

    private val userId get() = inputData.getString(DownloadWork.KEY_USER).orEmpty()
    private val asin get() = inputData.getString(DownloadWork.KEY_ASIN).orEmpty()

    override suspend fun getForegroundInfo(): ForegroundInfo = deps.notifications.foregroundInfo(asin, null, preparing = true)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    override suspend fun doWork(): Result {
        if (userId.isEmpty() || asin.isEmpty()) return Result.failure()
        try {
            setForeground(deps.notifications.foregroundInfo(asin, null, preparing = true))
        } catch (_: Exception) {
            // Starting a foreground service from the background can be refused (Android 12+); the job still runs, just without the notification.
        }
        return coroutineScope {
            val updater = launch {
                deps.bus.live.map { it[asin] }.sample(1_000).collect { progress ->
                    try {
                        setForeground(deps.notifications.foregroundInfo(asin, progress, preparing = progress == null))
                    } catch (_: Exception) {
                    }
                }
            }
            try {
                map(deps.runner.run(userId, asin))
            } catch (e: CancellationException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) requeueAfterTimeout()
                throw e
            } finally {
                updater.cancel()
            }
        }
    }

    /** Android 15 stops a dataSync service after about 6 h a day: ask for a fresh, non-expedited run with the job's own constraints (Range resumes the files). */
    private fun requeueAfterTimeout() {
        val user = userId
        val book = asin
        CoroutineScope(Dispatchers.IO).launch { runCatching { deps.maintenance.requeue(user, book, expedited = false) } }
    }

    private fun map(outcome: RunOutcome): Result = when (outcome) {
        RunOutcome.Done, RunOutcome.Paused -> Result.success()
        RunOutcome.Retry, is RunOutcome.Waiting -> Result.retry()
        RunOutcome.Failed, RunOutcome.Gone -> Result.failure()
    }
}

/** Housekeeping once or twice a day. */
class MaintenanceWorker(
    context: Context,
    params: WorkerParameters,
    private val deps: DownloadWorkerDeps,
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, DownloadWorkerDeps.from(context))

    override suspend fun doWork(): Result = try {
        deps.maintenance.run()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Result.retry()
    }
}
