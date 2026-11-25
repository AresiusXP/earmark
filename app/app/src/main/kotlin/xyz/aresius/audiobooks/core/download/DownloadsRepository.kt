package xyz.aresius.audiobooks.core.download

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.ReleaseView

/**
 * The downloads of the signed-in user: what the screens (Book detail, Downloads, Library badges) and the player observe and start.
 *
 * For the player: [state] per book; [autoDownloadIfEnabled] when it starts a book (honours the user's "auto-download the book I'm
 * listening to on Wi-Fi" setting); files for playback come from [LocalFiles], not from here.
 */
@Singleton
class DownloadsRepository(
    private val dao: DownloadsDao,
    private val sessions: SessionStore,
    private val storage: StorageManager,
    private val settings: DownloadSettings,
    private val network: NetworkStatus,
    private val bus: ProgressBus,
    private val scheduler: DownloadScheduler,
    private val torrentAvailable: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject
    constructor(
        dao: DownloadsDao, sessions: SessionStore, storage: StorageManager, settings: DownloadSettings, network: NetworkStatus,
        bus: ProgressBus, scheduler: DownloadScheduler, torrent: java.util.Optional<xyz.aresius.audiobooks.core.torrent.TorrentEngine>,
    ) : this(dao, sessions, storage, settings, network, bus, scheduler, { torrent.isPresent }, System::currentTimeMillis)

    private val userId: Flow<String?> = sessions.session.map { it?.userId }.distinctUntilChanged()

    val prefs: Flow<DownloadPrefs> get() = settings.prefs

    @OptIn(ExperimentalCoroutinesApi::class)
    fun state(asin: String): Flow<BookDownloadState> = userId.flatMapLatest { user ->
        if (user == null) {
            flowOf(BookDownloadState.None)
        } else {
            combine(dao.observeBook(user, asin), dao.observeBookFiles(user, asin), bus.live.map { it[asin] }.distinctUntilChanged(), network.kind, settings.prefs) { book, files, live, net, prefs ->
                DownloadStateLogic.derive(book, files, live, net, prefs.allowMobileData)
            }
        }
    }.distinctUntilChanged()

    /** The release this book's download job is for (null = no job). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun releaseHash(asin: String): Flow<String?> = userId.flatMapLatest { user ->
        if (user == null) flowOf(null) else dao.observeBook(user, asin).map { it?.releaseHash }
    }.distinctUntilChanged()

    /** Every book with a download job, newest first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val downloads: Flow<List<DownloadedBook>> = userId.flatMapLatest { user ->
        if (user == null) {
            flowOf(emptyList())
        } else {
            combine(dao.observeBooks(user), dao.observeFiles(user), bus.live, network.kind, settings.prefs) { books, files, live, net, prefs ->
                val byAsin = files.groupBy { it.asin }
                books.mapNotNull { row ->
                    val book = runCatching { AudiobooksJson.decodeFromString(Book.serializer(), row.bookJson) }.getOrNull() ?: return@mapNotNull null
                    val rows = byAsin[row.asin].orEmpty()
                    DownloadedBook(
                        book, row.releaseHash,
                        DownloadStateLogic.derive(row, rows, live[row.asin], net, prefs.allowMobileData),
                        rows.sumOf { if (it.state == FileState.COMPLETE) it.size else it.bytesDone },
                        row.completedAtMs,
                    )
                }
            }
        }
    }

    /** Books that are fully downloaded (the "Downloaded" badge). */
    val downloadedAsins: Flow<Set<String>> = downloads.map { list -> list.filter { it.state.isCompleted }.map { it.book.asin }.toSet() }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val storageInfo: Flow<StorageInfo> = userId.flatMapLatest { user ->
        if (user == null) {
            flowOf(StorageInfo(0, DownloadPrefs.DEFAULT_LIMIT_BYTES, storage.freeBytes()))
        } else {
            combine(dao.observeFiles(user), settings.prefs) { files, prefs ->
                StorageInfo(files.sumOf { if (it.state == FileState.COMPLETE) it.size else it.bytesDone }, prefs.storageLimitBytes, storage.freeBytes())
            }
        }
    }.distinctUntilChanged()

    suspend fun enqueue(book: Book, release: ReleaseView, choice: DownloadChoice?, unmeteredOnly: Boolean = false): EnqueueResult {
        val userId = sessions.load()?.userId ?: return EnqueueResult.NotSignedIn
        if (choice == null) return EnqueueResult.NeedsKey
        if (choice is DownloadChoice.Torrent && !torrentAvailable()) return EnqueueResult.Rejected(FailReason.TorrentUnavailable)
        if (!PathSafety.isSafeId(book.asin) || !PathSafety.isSafeId(release.infoHash)) return EnqueueResult.Rejected(FailReason.InvalidPath)
        val existing = dao.book(userId, book.asin)
        var keep: Map<String, DownloadFileEntity> = emptyMap()
        if (existing != null) {
            if (existing.releaseHash == release.infoHash) {
                when (existing.state) {
                    JobState.COMPLETED -> return EnqueueResult.AlreadyDownloaded
                    JobState.FAILED -> keep = dao.files(userId, book.asin).associateBy { it.path }
                    else -> return EnqueueResult.AlreadyActive
                }
            } else {
                scheduler.cancel(userId, book.asin)
                storage.deleteBook(userId, book.asin)
            }
        }
        val listed = FilePlanner.listed(release, preferTorrent = choice is DownloadChoice.Torrent)
        val rows = try {
            FilePlanner.rows(userId, book.asin, release.infoHash, listed, keep)
        } catch (e: DownloadException) {
            return EnqueueResult.Rejected(e.reason)
        }
        if (rows.isEmpty() && release.collection) return EnqueueResult.Rejected(FailReason.PackFilesUnknown)
        val need = if (rows.isNotEmpty() && rows.all { it.expectedSize > 0 }) rows.sumOf { (it.expectedSize - it.bytesDone).coerceAtLeast(0) } else release.size
        try {
            storage.ensureRoom(userId, book.asin, need)
        } catch (e: DownloadException) {
            return EnqueueResult.Rejected(e.reason)
        }
        val now = clock()
        val entity = DownloadBookEntity(
            userId = userId, asin = book.asin, releaseHash = release.infoHash, source = choice.wire, magnet = release.magnet,
            bookJson = AudiobooksJson.encodeToString(Book.serializer(), book), sizeHint = release.size, collection = release.collection,
            state = JobState.QUEUED, failReason = null, failMessage = null, retryAfterSec = null, pauseReason = null, preparePercent = null,
            slowReason = null, unmeteredOnly = unmeteredOnly, attempts = 0, preparingSinceMs = null, createdAtMs = now, updatedAtMs = now, completedAtMs = null,
        )
        dao.replaceBook(entity, rows)
        scheduler.enqueue(userId, book.asin, unmeteredOnly, settings.current().allowMobileData, replace = true)
        return EnqueueResult.Started
    }

    /** Starts the download of the book that is about to be played, when the user turned that on (always Wi-Fi only; never replaces a job). */
    suspend fun autoDownloadIfEnabled(book: Book, release: ReleaseView, preferredProvider: String? = null): EnqueueResult? {
        if (!settings.current().autoDownloadWhileListening) return null
        val userId = sessions.load()?.userId ?: return null
        if (dao.book(userId, book.asin) != null) return null
        return enqueue(book, release, DownloadChoices.choose(release, preferredProvider), unmeteredOnly = true)
    }

    /** A failed download starts again (what is already on disk is kept). */
    suspend fun retry(asin: String) {
        val userId = sessions.load()?.userId ?: return
        val changed = dao.updateBook(userId, asin) {
            if (it.state != JobState.FAILED) it else it.copy(
                state = JobState.QUEUED, failReason = null, failMessage = null, retryAfterSec = null, attempts = 0, preparingSinceMs = null, updatedAtMs = clock(),
            )
        }
        if (changed) scheduler.enqueue(userId, asin, dao.book(userId, asin)?.unmeteredOnly ?: false, settings.current().allowMobileData, replace = true)
    }

    /**
     * Pause: the job keeps its place and its partial files (`.part` + the bytes done) but no work is scheduled, so nothing runs until [resume].
     * The row is marked first (a running job notices it at its next write and stops), then the work is cancelled.
     */
    suspend fun pause(asin: String) {
        val userId = sessions.load()?.userId ?: return
        if (dao.book(userId, asin)?.state !in JobState.ACTIVE) return
        dao.updateBook(userId, asin) {
            if (it.state !in JobState.ACTIVE) it else it.copy(
                state = JobState.USER_PAUSED, pauseReason = null, preparePercent = null, slowReason = null, updatedAtMs = clock(),
            )
        }
        scheduler.cancel(userId, asin)
        bus.clear(asin)
    }

    /** Continues a paused download where it stopped (the files fetch with Range from what is on disk). */
    suspend fun resume(asin: String) {
        val userId = sessions.load()?.userId ?: return
        val job = dao.book(userId, asin)?.takeIf { it.state == JobState.USER_PAUSED } ?: return
        dao.updateBook(userId, asin) {
            if (it.state != JobState.USER_PAUSED) it else it.copy(state = JobState.QUEUED, attempts = 0, preparingSinceMs = null, updatedAtMs = clock())
        }
        scheduler.enqueue(userId, asin, job.unmeteredOnly, settings.current().allowMobileData, replace = true)
    }

    /** Stops the download of [asin] and deletes everything of it (finished or not). */
    suspend fun delete(asin: String) {
        val userId = sessions.load()?.userId ?: return
        scheduler.cancel(userId, asin)
        storage.deleteBook(userId, asin)
        bus.clear(asin)
    }

    suspend fun deleteAll() {
        val userId = sessions.load()?.userId ?: return
        scheduler.cancelAll()
        storage.deleteAll(userId)
    }

    /** Sign-out / user switch: no downloaded byte of the old account stays on the phone. */
    suspend fun wipe() {
        scheduler.cancelAll()
        storage.wipeEverything()
    }
}
