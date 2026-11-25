package xyz.aresius.audiobooks.core.download

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Book

sealed interface RunOutcome {
    /** Every file is complete. */
    data object Done : RunOutcome

    /** A transient problem: WorkManager retries with exponential backoff. */
    data object Retry : RunOutcome

    /** Not allowed to run now (Wi-Fi only, no network): WorkManager runs it again when its constraints hold. */
    data class Waiting(val reason: WaitReason) : RunOutcome

    /** The job ended in [JobState.FAILED] (the reason is on the row). */
    data object Failed : RunOutcome

    /** The book was deleted, or the signed-in user changed. */
    data object Gone : RunOutcome

    /** The user paused the job: nothing is retried until Resume. */
    data object Paused : RunOutcome
}

/** Picks the [DownloadSource] for a job's `source`. */
fun interface DownloadSources {
    fun forSource(source: DownloadChoice): DownloadSource
}

/**
 * Runs one book's download job: checks the policy (Wi-Fi only / mobile data), the storage, lets the [DownloadSource] do the work and
 * records the outcome on the job row. It is a plain class (no Android types) so it is tested with MockWebServer and a fake network.
 */
@Singleton
class DownloadRunner(
    private val dao: DownloadsDao,
    private val sessions: SessionStore,
    private val storage: StorageManager,
    private val settings: DownloadSettings,
    private val network: NetworkStatus,
    private val sources: DownloadSources,
    private val bus: ProgressBus,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject
    constructor(
        dao: DownloadsDao, sessions: SessionStore, storage: StorageManager, settings: DownloadSettings, network: NetworkStatus,
        sources: DownloadSources, bus: ProgressBus,
    ) : this(dao, sessions, storage, settings, network, sources, bus, System::currentTimeMillis)

    suspend fun run(userId: String, asin: String): RunOutcome {
        val job = dao.book(userId, asin) ?: return RunOutcome.Gone
        if (sessions.snapshot()?.userId != userId) return RunOutcome.Gone
        if (job.state == JobState.COMPLETED) return RunOutcome.Done
        if (job.state == JobState.FAILED) return RunOutcome.Failed
        if (job.state == JobState.USER_PAUSED) return RunOutcome.Paused
        val gate = DownloadGate.check(network.kind.value, settings.current().allowMobileData, job.unmeteredOnly)
        if (gate is Gate.Wait) return RunOutcome.Waiting(gate.reason)
        val dir = storage.bookDir(job.asin, job.releaseHash)
        val choice = DownloadChoice.fromWire(job.source)
        if (dir == null || choice == null) {
            fail(job, DownloadException(FailReason.InvalidPath, "Unsafe identifiers"))
            return RunOutcome.Failed
        }
        val book = runCatching { AudiobooksJson.decodeFromString(Book.serializer(), job.bookJson) }.getOrNull()
        val ctx = JobContext(job, dir, dao, storage, bus, clock, book?.title.orEmpty())
        return try {
            dao.updateBook(userId, asin) { it.copy(state = if (it.state == JobState.QUEUED) JobState.PREPARING else it.state, preparingSinceMs = clock(), updatedAtMs = clock()) }
            dir.mkdirs()
            ctx.checkSpace()
            sources.forSource(choice).run(ctx)
            val rows = ctx.files()
            if (rows.isEmpty() || rows.any { it.state != FileState.COMPLETE }) throw DownloadException(FailReason.Unknown, "The download ended with files missing", retryable = true)
            dao.updateBook(userId, asin) {
                it.copy(state = JobState.COMPLETED, failReason = null, failMessage = null, retryAfterSec = null, pauseReason = null, preparePercent = null, slowReason = null, attempts = 0, completedAtMs = clock(), updatedAtMs = clock())
            }
            storage.enforceLimit(userId, keep = asin)
            RunOutcome.Done
        } catch (e: JobContext.JobGone) {
            RunOutcome.Gone
        } catch (e: JobContext.JobPaused) {
            RunOutcome.Paused
        } catch (e: CancellationException) {
            throw e // the worker was stopped: the row keeps its state and the work is rescheduled
        } catch (e: DownloadException) {
            handle(job, e)
        } catch (e: java.io.IOException) {
            handle(job, DownloadException(FailReason.Network, "I/O problem", retryable = true, cause = e))
        } finally {
            ctx.finish()
        }
    }

    private suspend fun handle(job: DownloadBookEntity, e: DownloadException): RunOutcome {
        if (!e.retryable) {
            fail(job, e)
            return RunOutcome.Failed
        }
        val attempts = (dao.book(job.userId, job.asin)?.attempts ?: return RunOutcome.Gone) + 1
        if (attempts >= MAX_ATTEMPTS) {
            fail(job, e)
            return RunOutcome.Failed
        }
        if (dao.book(job.userId, job.asin)?.state == JobState.USER_PAUSED) return RunOutcome.Paused
        dao.updateBook(job.userId, job.asin) { it.copy(attempts = attempts, updatedAtMs = clock()) }
        return RunOutcome.Retry
    }

    private suspend fun fail(job: DownloadBookEntity, e: DownloadException) {
        dao.updateBook(job.userId, job.asin) {
            if (it.state == JobState.USER_PAUSED) it else it.copy(
                state = JobState.FAILED, failReason = e.reason.wire, failMessage = e.message?.take(300), retryAfterSec = e.retryAfterSec,
                pauseReason = null, preparePercent = null, slowReason = null, updatedAtMs = clock(),
            )
        }
    }

    companion object {
        /** Failures in a row without any progress before a transient problem becomes a failed download. */
        const val MAX_ATTEMPTS = 8
    }
}
