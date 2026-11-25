package xyz.aresius.audiobooks.core.player

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiProvider
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.isFutureUpdatedAt
import xyz.aresius.audiobooks.core.network.LibraryEntry
import xyz.aresius.audiobooks.core.network.LibraryEntryWrite
import xyz.aresius.audiobooks.core.network.safeApiCall

/** The part of `/v1/me/library/{asin}` that progress sync uses. */
interface ProgressRemote {
    suspend fun get(asin: String): ApiResult<LibraryEntry>
    suspend fun put(asin: String, write: LibraryEntryWrite): ApiResult<LibraryEntry>
}

class ApiProgressRemote(private val provider: ApiProvider) : ProgressRemote {
    override suspend fun get(asin: String) = safeApiCall { provider.api().getLibraryEntry(asin) }
    override suspend fun put(asin: String, write: LibraryEntryWrite) = safeApiCall { provider.api().putLibraryEntry(asin, write) }
}

/** Asks for a retry of the pending writes once the network is back (WorkManager in the app). */
fun interface SyncScheduler {
    fun schedule()
}

/** Where the book stands: what the player resumes from and what is sent to the server. */
data class ResumePoint(
    val status: String,
    val releaseHash: String?,
    val positionMs: Long,
    val filePath: String?,
    val filePositionMs: Long,
    val speed: Double,
    val updatedAt: Long,
)

enum class SyncOutcome { Synced, Pending, Rejected }

/**
 * Progress: written to Room every few seconds (the local truth, survives a kill), sent to the server at most every [MIN_PUSH_INTERVAL_MS]
 * while playing and at once on pause/stop. A write that cannot reach the server stays `dirty` (the latest state per book is the queue) and
 * is retried by [flush] (app start, the worker, the next open of the book). Last write wins on the client clock, as in the contract.
 */
class ProgressSync(
    private val dao: PlayerDao,
    private val remote: ProgressRemote,
    private val scheduler: SyncScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lastPush = ConcurrentHashMap<String, Long>()

    /**
     * How far this phone's clock is from the server's (server - phone, ms), learned only when the server refused a write for a timestamp
     * more than 24 h in the future; from then on writes are stamped with the server's time. 0 until then (and after a process restart).
     */
    @Volatile private var clockOffsetMs = 0L

    /** Stores [point] as the newest local state of the book (stamped now) and sends it when due or [force]d. */
    suspend fun record(userId: String, asin: String, point: ResumePoint, force: Boolean): SyncOutcome {
        val stamped = point.copy(updatedAt = clock() + clockOffsetMs)
        dao.putProgress(stamped.toRow(userId, asin, dirty = true))
        val key = "$userId/$asin"
        val last = lastPush[key]
        if (!force && last != null && monotonic() - last < MIN_PUSH_INTERVAL_MS) return SyncOutcome.Pending
        return push(userId, asin)
    }

    /** Sends the stored row if it is dirty. */
    suspend fun push(userId: String, asin: String): SyncOutcome {
        val row = dao.progress(userId, asin) ?: return SyncOutcome.Synced
        if (!row.dirty) return SyncOutcome.Synced
        lastPush["$userId/$asin"] = monotonic()
        val write = LibraryEntryWrite(
            status = row.status,
            releaseHash = row.releaseHash,
            positionMs = row.positionMs,
            filePath = row.filePath,
            filePositionMs = row.filePositionMs,
            speed = row.speed,
            updatedAt = row.updatedAt,
        )
        return when (val r = remote.put(asin, write)) {
            is ApiResult.Success -> {
                confirmed(userId, asin, row, row.updatedAt, r.value)
                SyncOutcome.Synced
            }
            is ApiResult.Failure -> when {
                r.error.isFutureUpdatedAt -> repairFutureStamp(userId, asin, row, write, r.error as ApiError.Http)
                r.error.isPermanentForWrites() -> {
                    dao.markClean(userId, asin, row.updatedAt)
                    SyncOutcome.Rejected
                }
                else -> {
                    scheduler.schedule()
                    SyncOutcome.Pending
                }
            }
        }
    }

    /** The server accepted [sentAt] as the row's `updated_at`: the row is clean (when this phone has not written again meanwhile). */
    private suspend fun confirmed(userId: String, asin: String, row: ProgressEntity, sentAt: Long, stored: LibraryEntry) {
        val unchanged = dao.progress(userId, asin)?.updatedAt == row.updatedAt
        if (sentAt != row.updatedAt && unchanged) dao.putProgress(row.copy(updatedAt = sentAt, dirty = false)) else dao.markClean(userId, asin, row.updatedAt)
        // The server keeps a newer write of another device: adopt it unless this phone wrote again meanwhile.
        if (stored.updatedAt > sentAt && unchanged) dao.putProgress(stored.toRow(userId, dirty = false))
    }

    /**
     * A 400 for an `updated_at` more than 24 h ahead of the server: this phone's clock is far off. The row is NOT dropped. When the server
     * sent its `Date`, the write is re-stamped with the server's time (clamped, never later than the original) and sent once more, and later
     * writes are stamped with the learned offset. Without a server date the row stays dirty and is retried later (the retry cannot make the
     * stamp valid by itself, but nothing is lost, and a corrected clock fixes it).
     */
    private suspend fun repairFutureStamp(userId: String, asin: String, row: ProgressEntity, write: LibraryEntryWrite, error: ApiError.Http): SyncOutcome {
        val serverNow = error.serverDateMs
        if (serverNow == null || serverNow < 0) {
            scheduler.schedule()
            return SyncOutcome.Pending
        }
        val fixed = minOf(row.updatedAt, serverNow)
        clockOffsetMs = serverNow - clock()
        return when (val r = remote.put(asin, write.copy(updatedAt = fixed))) {
            is ApiResult.Success -> {
                confirmed(userId, asin, row, fixed, r.value)
                SyncOutcome.Synced
            }
            is ApiResult.Failure -> if (!r.error.isFutureUpdatedAt && r.error.isPermanentForWrites()) {
                dao.markClean(userId, asin, row.updatedAt)
                SyncOutcome.Rejected
            } else {
                scheduler.schedule()
                SyncOutcome.Pending
            }
        }
    }

    /** Sends every dirty row of the user. Returns true when nothing is left pending. */
    suspend fun flush(userId: String): Boolean {
        var allDone = true
        for (row in dao.dirtyProgress(userId)) if (push(userId, row.asin) == SyncOutcome.Pending) allDone = false
        return allDone
    }

    /**
     * Where to resume [asin]: the newer of the local row and the server's entry (`updated_at`; a tie keeps the local one). A newer
     * server entry replaces the local row; a newer local row that was never confirmed is pushed. Null when neither knows the book.
     */
    suspend fun resolve(userId: String, asin: String): ResumePoint? {
        val local = dao.progress(userId, asin)
        // A slow network must not hold the start of playback: the local row is enough then.
        val remoteEntry = (withTimeoutOrNull(REMOTE_TIMEOUT_MS) { remote.get(asin) } as? ApiResult.Success)?.value
        if (remoteEntry != null && (local == null || remoteEntry.updatedAt > local.updatedAt)) {
            dao.putProgress(remoteEntry.toRow(userId, dirty = false))
            return remoteEntry.toPoint()
        }
        if (local != null && local.dirty) push(userId, asin)
        return local?.toPoint()
    }

    suspend fun pending(): Boolean = dao.dirtyCount() > 0

    private fun ApiError.isPermanentForWrites(): Boolean = this is ApiError.Http && status in setOf(400, 404, 409)

    companion object {
        const val MIN_PUSH_INTERVAL_MS = 30_000L
        const val REMOTE_TIMEOUT_MS = 4_000L
    }
}

internal fun ResumePoint.toRow(userId: String, asin: String, dirty: Boolean) =
    ProgressEntity(userId, asin, status, releaseHash, positionMs, filePath, filePositionMs, speed, updatedAt, dirty)

internal fun LibraryEntry.toRow(userId: String, dirty: Boolean) =
    ProgressEntity(userId, book.asin, status, releaseHash, positionMs, filePath, filePositionMs, speed, updatedAt, dirty)

internal fun ProgressEntity.toPoint() = ResumePoint(status, releaseHash, positionMs, filePath, filePositionMs, speed, updatedAt)

internal fun LibraryEntry.toPoint() = ResumePoint(status, releaseHash, positionMs, filePath, filePositionMs, speed, updatedAt)

