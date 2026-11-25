package xyz.aresius.audiobooks.core.download

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Live numbers of a running download (not persisted: speed and ETA only mean something while it runs). */
data class LiveProgress(
    val bytesDone: Long,
    val bytesTotal: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Long?,
    val filesDone: Int,
    val filesTotal: Int,
    val title: String = "",
)

@Singleton
class ProgressBus @Inject constructor() {
    private val state = MutableStateFlow<Map<String, LiveProgress>>(emptyMap())
    val live: StateFlow<Map<String, LiveProgress>> = state

    fun update(asin: String, progress: LiveProgress) {
        state.value = state.value + (asin to progress)
    }

    fun clear(asin: String) {
        if (asin in state.value) state.value = state.value - asin
    }
}

/** A source of the files of a book. [run] returns when every planned file is complete, or throws [DownloadException]. */
interface DownloadSource {
    suspend fun run(ctx: JobContext)
}

/** The job a [DownloadSource] works on, and the only way it writes state: rows, files and live progress. Throws [JobGone] when the book was deleted meanwhile. */
class JobContext(
    val job: DownloadBookEntity,
    val dir: File,
    private val dao: DownloadsDao,
    private val storage: StorageManager,
    private val bus: ProgressBus,
    private val clock: () -> Long,
    private val title: String,
) {
    class JobGone : CancellationException("download removed")

    val userId get() = job.userId
    val asin get() = job.asin
    val releaseHash get() = job.releaseHash
    val magnet get() = job.magnet
    val source get() = job.source
    val collection get() = job.collection

    private val bytes = HashMap<String, Long>()
    private var lastDbWrite = 0L
    private var lastSpeedAt = 0L
    private var lastSpeedBytes = 0L
    private var speed = 0.0
    private var totalDone = 0L
    private var started = false

    suspend fun files(): List<DownloadFileEntity> = dao.files(userId, asin)

    suspend fun pendingFiles(): List<DownloadFileEntity> = files().filter { it.state != FileState.COMPLETE }

    /** The safe location of [file] below the book directory. */
    fun fileOf(file: DownloadFileEntity): File =
        PathSafety.resolve(dir, file.localPath) ?: throw DownloadException(FailReason.InvalidPath, "Unsafe file path")

    suspend fun setQueuedOrPreparing(percent: Int?, slow: FailReason?) {
        update { it.copy(state = JobState.PREPARING, preparePercent = percent, slowReason = slow?.wire, pauseReason = null) }
    }

    suspend fun setDownloading() {
        update { if (it.state == JobState.DOWNLOADING) it else it.copy(state = JobState.DOWNLOADING, preparePercent = null, slowReason = null, pauseReason = null) }
    }

    suspend fun setPaused(reason: WaitReason) {
        update { it.copy(state = JobState.PAUSED, pauseReason = reason.name) }
    }

    /** Records (and creates the directory for) the final list of files, keeps what is already complete, and checks storage for the rest. */
    suspend fun planFiles(rows: List<DownloadFileEntity>) {
        check(rows.all { it.userId == userId && it.asin == asin })
        val existing = files().associateBy { it.path }
        val merged = rows.map { r -> existing[r.path]?.let { old -> r.copy(bytesDone = old.bytesDone, size = old.size, state = old.state) } ?: r }
        if (dao.book(userId, asin) == null) throw JobGone()
        dao.replaceFiles(userId, asin, merged)
        merged.forEach { if (it.state == FileState.COMPLETE) bytes[it.path] = it.size else bytes[it.path] = it.bytesDone }
        totalDone = bytes.values.sum()
        merged.forEach { fileOf(it) } // validates every path against the directory
        checkSpace()
    }

    suspend fun checkSpace() {
        val rows = files()
        val need = if (rows.isEmpty() || rows.any { it.expectedSize <= 0 }) {
            (job.sizeHint - rows.sumOf { it.bytesDone }).coerceAtLeast(0)
        } else {
            rows.filter { it.state != FileState.COMPLETE }.sumOf { (it.expectedSize - it.bytesDone).coerceAtLeast(0) }
        }
        storage.ensureRoom(userId, asin, need)
    }

    /** Bytes on disk of one file (throttled to the database, immediate to the live numbers). */
    suspend fun fileProgress(file: DownloadFileEntity, bytesOnDisk: Long) {
        bytes[file.path] = bytesOnDisk
        totalDone = bytes.values.sum()
        val now = clock()
        if (now - lastDbWrite >= DB_INTERVAL_MS) {
            lastDbWrite = now
            dao.setBytes(userId, asin, releaseHash, file.path, bytesOnDisk)
            update { it.copy(attempts = 0) }
        }
        publish(now)
    }

    suspend fun fileComplete(file: DownloadFileEntity, size: Long) {
        dao.markComplete(userId, asin, releaseHash, file.path, size)
        bytes[file.path] = size
        totalDone = bytes.values.sum()
        update { it.copy(attempts = 0) }
        publish(clock(), force = true)
    }

    /** For engines that report totals instead of per-file bytes (torrents). */
    suspend fun totals(done: Long, total: Long, speedBytesPerSec: Long, etaSeconds: Long?) {
        totalDone = done
        speed = speedBytesPerSec.toDouble()
        val rows = files()
        bus.update(asin, LiveProgress(done, total, speedBytesPerSec, etaSeconds, rows.count { it.state == FileState.COMPLETE }, rows.size, title))
        val now = clock()
        if (now - lastDbWrite >= DB_INTERVAL_MS) {
            lastDbWrite = now
            update { it.copy(attempts = 0) }
        }
    }

    private suspend fun publish(now: Long, force: Boolean = false) {
        if (!started) {
            started = true
            lastSpeedAt = now
            lastSpeedBytes = totalDone
        } else if (now - lastSpeedAt >= SPEED_WINDOW_MS) {
            val instant = (totalDone - lastSpeedBytes) * 1000.0 / (now - lastSpeedAt)
            speed = if (speed <= 0) instant else speed * 0.6 + instant * 0.4
            lastSpeedAt = now
            lastSpeedBytes = totalDone
        }
        val rows = if (force) files() else null
        val total = knownTotal()
        val remaining = (total - totalDone).coerceAtLeast(0)
        val eta = if (speed >= 1 && total > 0) (remaining / speed).toLong() else null
        val previous = bus.live.value[asin]
        bus.update(
            asin,
            LiveProgress(
                totalDone, total, speed.toLong(), eta,
                rows?.count { it.state == FileState.COMPLETE } ?: previous?.filesDone ?: 0,
                rows?.size ?: previous?.filesTotal ?: 0, title,
            ),
        )
    }

    private var cachedTotal = 0L
    private var cachedTotalAt = 0L

    private suspend fun knownTotal(): Long {
        val now = clock()
        if (cachedTotal == 0L || now - cachedTotalAt > 5_000) {
            val rows = files()
            cachedTotal = if (rows.isNotEmpty() && rows.all { it.expectedSize > 0 }) rows.sumOf { it.expectedSize } else job.sizeHint
            cachedTotalAt = now
        }
        return cachedTotal
    }

    private suspend fun update(change: (DownloadBookEntity) -> DownloadBookEntity) {
        if (!dao.updateBook(userId, asin) { change(it).copy(updatedAtMs = clock()) }) throw JobGone()
    }

    fun finish() = bus.clear(asin)

    private companion object {
        const val DB_INTERVAL_MS = 2_000L
        const val SPEED_WINDOW_MS = 1_000L
    }
}
