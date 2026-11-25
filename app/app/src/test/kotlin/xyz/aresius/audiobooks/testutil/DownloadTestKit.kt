package xyz.aresius.audiobooks.testutil

import android.content.Context
import androidx.room.Room
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.data.ReleaseRepository
import xyz.aresius.audiobooks.core.download.DebridSource
import xyz.aresius.audiobooks.core.download.DownloadBookEntity
import xyz.aresius.audiobooks.core.download.DownloadChoice
import xyz.aresius.audiobooks.core.download.DownloadFileEntity
import xyz.aresius.audiobooks.core.download.FileState
import xyz.aresius.audiobooks.core.download.JobState
import xyz.aresius.audiobooks.core.download.DownloadPrefs
import xyz.aresius.audiobooks.core.download.DownloadRunner
import xyz.aresius.audiobooks.core.download.DownloadScheduler
import xyz.aresius.audiobooks.core.download.DownloadSettings
import xyz.aresius.audiobooks.core.download.DownloadSource
import xyz.aresius.audiobooks.core.download.DownloadSources
import xyz.aresius.audiobooks.core.download.DownloadsDb
import xyz.aresius.audiobooks.core.download.DownloadsRepository
import xyz.aresius.audiobooks.core.download.FinishedBooks
import xyz.aresius.audiobooks.core.download.FreeSpace
import xyz.aresius.audiobooks.core.download.HttpFileFetcher
import xyz.aresius.audiobooks.core.download.NetKind
import xyz.aresius.audiobooks.core.download.NetworkStatus
import xyz.aresius.audiobooks.core.download.PlayingBook
import xyz.aresius.audiobooks.core.download.ProgressBus
import xyz.aresius.audiobooks.core.download.StorageManager
import xyz.aresius.audiobooks.core.download.TorrentSource
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.ReleaseView
import xyz.aresius.audiobooks.core.network.TorrentFile
import xyz.aresius.audiobooks.core.network.Usable
import xyz.aresius.audiobooks.core.torrent.TorrentEngine

class FakeNetwork(initial: NetKind = NetKind.Unmetered) : NetworkStatus {
    private val state = MutableStateFlow(initial)
    override val kind: StateFlow<NetKind> = state
    fun set(kind: NetKind) {
        state.value = kind
    }
}

class MemoryDownloadSettings(initial: DownloadPrefs = DownloadPrefs()) : DownloadSettings {
    private val state = MutableStateFlow(initial)
    override val prefs: Flow<DownloadPrefs> = state
    override suspend fun setAllowMobileData(value: Boolean) { state.value = state.value.copy(allowMobileData = value) }
    override suspend fun setStorageLimit(bytes: Long) { state.value = state.value.copy(storageLimitBytes = bytes) }
    override suspend fun setAutoDownloadWhileListening(value: Boolean) { state.value = state.value.copy(autoDownloadWhileListening = value) }
    override suspend fun setAutoDeleteAfterDays(days: Int) { state.value = state.value.copy(autoDeleteAfterDays = days) }
}

class RecordingScheduler : DownloadScheduler {
    data class Call(val userId: String, val asin: String, val unmeteredOnly: Boolean, val allowMobile: Boolean, val replace: Boolean, val expedited: Boolean)

    val enqueued = ArrayList<Call>()
    val cancelled = ArrayList<String>()
    var cancelAllCount = 0
    var maintenance = 0

    override fun enqueue(userId: String, asin: String, unmeteredOnly: Boolean, allowMobileData: Boolean, replace: Boolean, expedited: Boolean) {
        enqueued.add(Call(userId, asin, unmeteredOnly, allowMobileData, replace, expedited))
    }

    override fun cancel(userId: String, asin: String) {
        cancelled.add("$userId:$asin")
    }

    override fun cancelAll() {
        cancelAllCount++
    }

    override fun ensureMaintenanceScheduled() {
        maintenance++
    }
}

class FakePlaying(var asin: String? = null) : PlayingBook {
    override fun currentAsin() = asin
}

class FakeFinished(val map: MutableMap<String, Long> = HashMap()) : FinishedBooks {
    override suspend fun finishedAtMs(userId: String): Map<String, Long> = map.toMap()
}

object Audio {
    /** Deterministic "audio": the bytes of [name] repeated, [size] long. */
    fun bytes(name: String, size: Int): ByteArray = ByteArray(size) { i -> (name.hashCode() + i * 31).toByte() }

    fun response(data: ByteArray, type: String = "audio/mpeg", code: Int = 200, vararg headers: Pair<String, String>): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", type).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.body(Buffer().write(data)).build()
}

fun sampleBook(asin: String = "B000000001") = Book(asin = asin, title = "Pride and Prejudice", authors = listOf("Jane Austen"), runtimeMin = 600)

fun sampleRelease(
    hash: String = "a".repeat(40),
    files: List<TorrentFile> = emptyList(),
    torrentFiles: List<TorrentFile> = emptyList(),
    collection: Boolean = false,
    size: Long = files.sumOf { it.size },
    usable: Usable = Usable(debrid = listOf("torbox"), p2p = false),
) = ReleaseView(infoHash = hash, title = "P&P", size = size, files = files, torrentFiles = torrentFiles, collection = collection, magnet = "magnet:?xt=urn:btih:$hash", usable = usable, cached = mapOf("torbox" to true))

/** Everything of core/download wired with fakes: real Room (in memory), real files in a temp directory, MockWebServer as the CDN. */
class DownloadKit(
    context: Context,
    val app: TestApp,
    releases: ReleaseRepository = app.releases,
    cdnClient: OkHttpClient = OkHttpClient(),
    freeBytes: Long = Long.MAX_VALUE / 2,
    prefs: DownloadPrefs = DownloadPrefs(),
    torrent: TorrentEngine? = null,
    var now: Long = 1_700_000_000_000L,
) : AutoCloseable {
    val db: DownloadsDb = Room.inMemoryDatabaseBuilder(context, DownloadsDb::class.java).allowMainThreadQueries().build()
    val dao = db.downloadsDao()
    val root: File = Files.createTempDirectory("books").toFile()
    val network = FakeNetwork()
    val settings = MemoryDownloadSettings(prefs)
    val scheduler = RecordingScheduler()
    val playing = FakePlaying()
    val finished = FakeFinished()
    val bus = ProgressBus()
    var free = freeBytes
    val sessions: SessionStore = app.sessions
    val storage = StorageManager(dao, root, settings, finished, playing, FreeSpace { free }, { now }, minFreeBytes = 0, orphanGraceMs = 0)
    val fetcher = HttpFileFetcher(cdnClient)
    val debrid = DebridSource(releases, fetcher, clock = { now }, pause = {})
    val torrentSource = TorrentSource({ torrent }, releases)
    var sourceOverride: DownloadSource? = null
    val runner = DownloadRunner(
        dao, sessions, storage, settings, network,
        DownloadSources { choice -> sourceOverride ?: if (choice is DownloadChoice.Torrent) torrentSource else debrid },
        bus, { now },
    )
    val repository = DownloadsRepository(dao, sessions, storage, settings, network, bus, scheduler, { torrent != null }, { now })

    /** A book with [sizes] bytes per file, really written to disk (complete) unless [complete] is false (then rows are pending, no files). */
    suspend fun seed(
        asin: String, sizes: List<Int> = listOf(1000), userId: String = "u1", hash: String = "b".repeat(40), state: String = JobState.COMPLETED,
        complete: Boolean = true, source: String = "torbox", createdAt: Long = now,
    ): List<File> {
        val book = DownloadBookEntity(
            userId = userId, asin = asin, releaseHash = hash, source = source, magnet = "", bookJson = AudiobooksJson.encodeToString(Book.serializer(), sampleBook(asin)),
            sizeHint = sizes.sum().toLong(), collection = false, state = state, failReason = null, failMessage = null, retryAfterSec = null, pauseReason = null,
            preparePercent = null, slowReason = null, unmeteredOnly = false, attempts = 0, preparingSinceMs = null, createdAtMs = createdAt, updatedAtMs = createdAt,
            completedAtMs = if (state == JobState.COMPLETED) createdAt else null,
        )
        val dir = File(File(root, asin), hash)
        val files = sizes.mapIndexed { i, size ->
            DownloadFileEntity(userId, asin, hash, "part$i.mp3", "part$i.mp3", i, i, size.toLong(), if (complete) size.toLong() else 0, if (complete) size.toLong() else 0, if (complete) FileState.COMPLETE else FileState.PENDING)
        }
        dao.replaceBook(book, files)
        return files.map { f ->
            File(dir, f.localPath).also { if (complete) { it.parentFile!!.mkdirs(); it.writeBytes(Audio.bytes(f.path, f.size.toInt())) } }
        }
    }

    override fun close() {
        db.close()
        root.deleteRecursively()
    }
}

fun cdn(): MockWebServer = MockWebServer().also { it.start() }
