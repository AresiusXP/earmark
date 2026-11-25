package xyz.aresius.audiobooks.core.download

import javax.inject.Inject
import javax.inject.Singleton
import xyz.aresius.audiobooks.core.auth.SessionStore

/** The periodic housekeeping (also run at start-up): orphans, vanished files, auto-delete, the storage limit, and a worker for every active job. */
@Singleton
class DownloadMaintenance @Inject constructor(
    private val dao: DownloadsDao,
    private val storage: StorageManager,
    private val sessions: SessionStore,
    private val settings: DownloadSettings,
    private val scheduler: DownloadScheduler,
) {
    suspend fun run() {
        storage.cleanupOrphans()
        val userId = sessions.load()?.userId ?: return
        storage.repairMissingFiles(userId)
        val prefs = settings.current()
        storage.autoDeleteFinished(userId, prefs.autoDeleteAfterDays)
        storage.enforceLimit(userId)
        reconcile(userId)
    }

    /** Makes sure every active job has its work in WorkManager (it normally has: this covers a lost or cleared work database). */
    suspend fun reconcile(userId: String) {
        val allowMobile = settings.current().allowMobileData
        dao.books(userId).filter { it.state in JobState.ACTIVE }.forEach {
            scheduler.enqueue(userId, it.asin, it.unmeteredOnly, allowMobile, replace = false)
        }
    }

    /** Enqueues the work of one job again (after the foreground-service quota ran out: not expedited). */
    suspend fun requeue(userId: String, asin: String, expedited: Boolean) {
        val job = dao.book(userId, asin)?.takeIf { it.state in JobState.ACTIVE } ?: return
        scheduler.enqueue(userId, asin, job.unmeteredOnly, settings.current().allowMobileData, replace = true, expedited = expedited)
    }

    /** The network policy changed: running workers restart with the right constraints. */
    suspend fun rescheduleActive(userId: String) {
        val allowMobile = settings.current().allowMobileData
        dao.books(userId).filter { it.state in JobState.ACTIVE }.forEach {
            scheduler.enqueue(userId, it.asin, it.unmeteredOnly, allowMobile, replace = true)
        }
    }
}
