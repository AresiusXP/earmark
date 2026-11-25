package xyz.aresius.audiobooks.core.download

import java.io.File

/**
 * Everything about what is on disk: usage, the storage limit with LRU eviction, auto-delete after finishing, deletion and orphan cleanup.
 *
 * Eviction only ever removes books the user has FINISHED (library status), oldest finish first; never the book that is playing,
 * never a book that is still downloading. When nothing is left to evict, a new download is refused with [FailReason.StorageLimit].
 */
class StorageManager(
    private val dao: DownloadsDao,
    val root: File,
    private val settings: DownloadSettings,
    private val finished: FinishedBooks,
    private val playing: PlayingBook,
    private val free: FreeSpace,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onBookRemoved: suspend (DownloadBookEntity) -> Unit = {},
    private val minFreeBytes: Long = MIN_FREE_BYTES,
    private val orphanGraceMs: Long = ORPHAN_GRACE_MS,
) {
    /** `root/<asin>/<releaseHash>`, or null when either id is not a safe directory name. */
    fun bookDir(asin: String, releaseHash: String): File? =
        if (PathSafety.isSafeId(asin) && PathSafety.isSafeId(releaseHash)) File(File(root, asin), releaseHash) else null

    suspend fun usedBytes(userId: String): Long =
        dao.allFiles().filter { it.userId == userId }.sumOf { if (it.state == FileState.COMPLETE) it.size else it.bytesDone }

    fun freeBytes(): Long = free.freeBytes()

    /**
     * Makes room for [need] more bytes for [asin]: checks the phone's free space and the storage limit, evicting finished books when
     * that helps. Throws [DownloadException] ([FailReason.NoSpace] / [FailReason.StorageLimit]) when it cannot.
     */
    suspend fun ensureRoom(userId: String, asin: String, need: Long) {
        if (need > 0 && free.freeBytes() < need + minFreeBytes) throw DownloadException(FailReason.NoSpace, "Not enough free space on this phone")
        val limit = settings.current().storageLimitBytes
        if (limit <= 0) return
        while (usedBytes(userId) + need.coerceAtLeast(0) > limit) {
            val victim = nextVictim(userId, except = asin) ?: throw DownloadException(FailReason.StorageLimit, "Storage limit reached")
            deleteBook(userId, victim.asin)
        }
    }

    /** After a book completed (or the limit was lowered): evicts finished books while the total is over the limit. */
    suspend fun enforceLimit(userId: String, keep: String? = null) {
        val limit = settings.current().storageLimitBytes
        if (limit <= 0) return
        while (usedBytes(userId) > limit) {
            val victim = nextVictim(userId, except = keep) ?: return
            deleteBook(userId, victim.asin)
        }
    }

    private suspend fun nextVictim(userId: String, except: String?): DownloadBookEntity? {
        val finishedAt = finished.finishedAtMs(userId)
        val playingNow = playing.currentAsin()
        return dao.books(userId)
            .filter { it.state == JobState.COMPLETED && it.asin != except && it.asin != playingNow && finishedAt.containsKey(it.asin) }
            .minByOrNull { finishedAt.getValue(it.asin) }
    }

    /** Deletes the books finished more than [days] ago (not the playing one). */
    suspend fun autoDeleteFinished(userId: String, days: Int) {
        if (days <= 0) return
        val cutoff = clock() - days * DAY_MS
        val finishedAt = finished.finishedAtMs(userId)
        val playingNow = playing.currentAsin()
        dao.books(userId)
            .filter { it.state == JobState.COMPLETED && it.asin != playingNow && (finishedAt[it.asin] ?: Long.MAX_VALUE) <= cutoff }
            .forEach { deleteBook(userId, it.asin) }
    }

    suspend fun deleteBook(userId: String, asin: String) {
        val book = dao.book(userId, asin)
        dao.removeBook(userId, asin)
        if (book != null) {
            bookDir(book.asin, book.releaseHash)?.deleteRecursively()
            onBookRemoved(book)
            // Leave no empty `<asin>` directory behind.
            File(root, book.asin).takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        }
    }

    suspend fun deleteAll(userId: String) {
        dao.books(userId).forEach { deleteBook(userId, it.asin) }
    }

    /** Wipe on sign-out / user switch: rows of every user and the whole directory. */
    suspend fun wipeEverything() {
        val all = dao.allBooks()
        dao.clearAll()
        root.deleteRecursively()
        all.forEach { onBookRemoved(it) }
    }

    /**
     * Files with no row are deleted (a crash between the file and its row, a removed book, another user's leftovers); so are `.part`
     * files of files that no longer exist as rows. Fresh files (younger than the grace period) are left alone: they may belong to a
     * download that is just being enqueued. Files inside the directory of a torrent-sourced book are left to the torrent engine.
     */
    suspend fun cleanupOrphans() {
        if (!root.isDirectory) return
        val books = dao.allBooks().associateBy { it.asin to it.releaseHash }
        val files = dao.allFiles()
        val known = files.groupBy { it.asin to it.releaseHash }.mapValues { (_, rows) -> rows.map { it.localPath }.toSet() }
        val cutoff = clock() - orphanGraceMs
        for (asinDir in root.listFiles().orEmpty()) {
            if (!asinDir.isDirectory) {
                if (asinDir.lastModified() <= cutoff) asinDir.delete()
                continue
            }
            for (hashDir in asinDir.listFiles().orEmpty()) {
                val key = asinDir.name to hashDir.name
                val book = books[key]
                if (!hashDir.isDirectory || book == null) {
                    if (newestModification(hashDir) <= cutoff) hashDir.deleteRecursively()
                } else if (book.source != DownloadChoice.TORRENT_SOURCE) {
                    sweepBookDir(hashDir, hashDir, known[key].orEmpty(), cutoff)
                }
            }
            if (asinDir.list().isNullOrEmpty()) asinDir.delete()
        }
    }

    private fun sweepBookDir(base: File, dir: File, knownPaths: Set<String>, cutoff: Long) {
        for (entry in dir.listFiles().orEmpty()) {
            if (entry.isDirectory) {
                sweepBookDir(base, entry, knownPaths, cutoff)
                if (entry.list().isNullOrEmpty()) entry.delete()
                continue
            }
            val relative = entry.relativeTo(base).invariantSeparatorsPath
            val owner = if (relative.endsWith(HttpFileFetcher.PART_SUFFIX)) relative.removeSuffix(HttpFileFetcher.PART_SUFFIX) else relative
            if (owner !in knownPaths && entry.lastModified() <= cutoff) entry.delete()
        }
    }

    private fun newestModification(file: File): Long =
        if (!file.isDirectory) file.lastModified() else maxOf(file.lastModified(), file.listFiles().orEmpty().maxOfOrNull { newestModification(it) } ?: 0L)

    /** Rows that say "complete" while the file is gone or has the wrong size become pending again; returns the asins affected. */
    suspend fun repairMissingFiles(userId: String): Set<String> {
        val affected = HashSet<String>()
        val books = dao.books(userId).associateBy { it.asin }
        for (row in dao.allFiles().filter { it.userId == userId && it.state == FileState.COMPLETE }) {
            val dir = bookDir(row.asin, row.releaseHash) ?: continue
            val file = PathSafety.resolve(dir, row.localPath)
            if (file == null || !file.isFile || file.length() != row.size) {
                dao.markPending(userId, row.asin, row.releaseHash, row.path)
                affected.add(row.asin)
            }
        }
        affected.forEach { asin ->
            val book = books[asin] ?: return@forEach
            if (book.state == JobState.COMPLETED) {
                dao.updateBook(userId, asin) { it.copy(state = JobState.QUEUED, attempts = 0, completedAtMs = null, updatedAtMs = clock()) }
            }
        }
        return affected
    }

    companion object {
        const val MIN_FREE_BYTES = 300L * 1024 * 1024
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val ORPHAN_GRACE_MS = 10L * 60 * 1000
    }
}
