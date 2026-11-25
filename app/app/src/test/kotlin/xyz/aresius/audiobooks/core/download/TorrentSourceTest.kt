package xyz.aresius.audiobooks.core.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.aresius.audiobooks.core.network.TorrentFile
import xyz.aresius.audiobooks.core.torrent.PauseReason
import xyz.aresius.audiobooks.core.torrent.StallReason
import xyz.aresius.audiobooks.core.torrent.TorrentDownload
import xyz.aresius.audiobooks.core.torrent.TorrentEngine
import xyz.aresius.audiobooks.core.torrent.TorrentFileInfo
import xyz.aresius.audiobooks.core.torrent.TorrentProgress
import xyz.aresius.audiobooks.testutil.Audio
import xyz.aresius.audiobooks.testutil.DownloadKit
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.sampleBook
import xyz.aresius.audiobooks.testutil.sampleRelease

private class FakeEngine(private val script: suspend (TorrentDownload) -> List<TorrentProgress>) : TorrentEngine {
    val removed = ArrayList<Pair<String, Boolean>>()
    var requests = ArrayList<TorrentDownload>()
    var listing: List<TorrentFileInfo> = emptyList()
    override suspend fun fetchFiles(infoHash: String, magnet: String) = listing
    override fun download(request: TorrentDownload): Flow<TorrentProgress> = flow {
        requests.add(request)
        script(request).forEach { emit(it) }
    }
    override suspend fun remove(infoHash: String, deleteFiles: Boolean) {
        removed.add(infoHash to deleteFiles)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TorrentSourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = FakeServer()
    private val app = TestApp(server)
    private val hash = "a".repeat(40)
    private val book = sampleBook()
    private val data = Audio.bytes("t", 4_000)
    private val files = listOf(TorrentFile(0, "Book/1.mp3", 4_000), TorrentFile(1, "Book/2.mp3", 4_000))
    private val release = sampleRelease(hash, torrentFiles = files, usable = xyz.aresius.audiobooks.core.network.Usable(p2p = true))
    private var kit: DownloadKit? = null

    @AfterTest fun tearDown() {
        kit?.close()
        server.close()
    }

    private fun kit(engine: TorrentEngine?) = DownloadKit(context, app, torrent = engine).also { kit = it }

    private fun write(request: TorrentDownload, vararg paths: String) = paths.forEach { p ->
        File(request.destination, p).apply { parentFile!!.mkdirs(); writeBytes(data) }
    }

    @Test fun withoutAnEngineTheTorrentSourceReportsUnavailable() = runBlocking<Unit> {
        val k = kit(null)
        assertEquals(EnqueueResult.Rejected(FailReason.TorrentUnavailable), k.repository.enqueue(book, release, DownloadChoice.Torrent))
        // A job that exists anyway (the engine went away) fails cleanly instead of crashing.
        k.seed(book.asin, listOf(4000, 4000), state = JobState.QUEUED, complete = false, source = "torrent")
        assertEquals(RunOutcome.Failed, k.runner.run("u1", book.asin))
        assertEquals(FailReason.TorrentUnavailable.wire, k.dao.book("u1", book.asin)!!.failReason)
    }

    @Test fun theEnginePlugsIntoThePipelineAndOnlyCompletedVerifiedFilesCount() = runBlocking<Unit> {
        val engine = FakeEngine { req ->
            write(req, "Book/1.mp3")
            val partial = File(req.destination, "Book/2.mp3").apply { writeBytes(ByteArray(1000)) } // engine file still downloading
            assertTrue(partial.exists())
            val mid = listOf(
                TorrentProgress.Starting(2),
                TorrentProgress.Downloading(4_000, 8_000, 1_000, 3, 1, 4, setOf("Book/1.mp3")),
            )
            write(req, "Book/2.mp3")
            mid + TorrentProgress.Finished(setOf("Book/1.mp3", "Book/2.mp3"))
        }
        val k = kit(engine)
        assertEquals(EnqueueResult.Started, k.repository.enqueue(book, release, DownloadChoice.Torrent))
        assertEquals(RunOutcome.Done, k.runner.run("u1", book.asin))
        assertEquals(listOf("Book/1.mp3", "Book/2.mp3"), engine.requests.single().selectedPaths)
        assertEquals(File(File(k.root, book.asin), hash).canonicalPath, engine.requests.single().destination.canonicalPath)
        assertTrue(k.dao.files("u1", book.asin).all { it.state == FileState.COMPLETE })
        assertEquals(JobState.COMPLETED, k.dao.book("u1", book.asin)!!.state)
    }

    @Test fun aFileTheEngineCallsCompleteButWithTheWrongSizeIsRejected() = runBlocking<Unit> {
        val engine = FakeEngine { req ->
            File(req.destination, "Book/1.mp3").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }
            listOf(TorrentProgress.Downloading(10, 8_000, 1, 1, 1, null, setOf("Book/1.mp3")))
        }
        val k = kit(engine)
        k.repository.enqueue(book, release, DownloadChoice.Torrent)
        assertEquals(RunOutcome.Failed, k.runner.run("u1", book.asin))
        assertEquals(FailReason.Integrity.wire, k.dao.book("u1", book.asin)!!.failReason)
        assertTrue(k.dao.files("u1", book.asin).none { it.state == FileState.COMPLETE })
    }

    @Test fun stalledAndPausedStatesAreMapped() = runBlocking<Unit> {
        val stalled = FakeEngine { listOf(TorrentProgress.Stalled(StallReason.NO_PEERS, 0)) }
        val k = kit(stalled)
        k.repository.enqueue(book, release, DownloadChoice.Torrent)
        assertEquals(RunOutcome.Failed, k.runner.run("u1", book.asin))
        assertEquals(FailReason.NoSeeders.wire, k.dao.book("u1", book.asin)!!.failReason)

        val paused = FakeEngine { listOf(TorrentProgress.Paused(PauseReason.VPN_REQUIRED)) }
        val k2 = kit(paused)
        k2.seed("P", listOf(4000), state = JobState.QUEUED, complete = false, source = "torrent")
        assertEquals(RunOutcome.Retry, k2.runner.run("u1", "P"), "the engine ended without finishing: retried later")
        assertEquals(WaitReason.Vpn.name, k2.dao.book("u1", "P")!!.pauseReason)

        val system = FakeEngine { listOf(TorrentProgress.Paused(PauseReason.SYSTEM)) }
        val k3 = kit(system)
        k3.seed("S", listOf(4000), state = JobState.QUEUED, complete = false, source = "torrent")
        k3.runner.run("u1", "S")
        assertEquals(WaitReason.Background.name, k3.dao.book("u1", "S")!!.pauseReason, "a refused foreground service shows as waiting for the system")
    }

    @Test fun deletingATorrentBookAsksTheEngineToDropItsData() = runBlocking<Unit> {
        val engine = FakeEngine { emptyList() }
        val k = kit(engine)
        val storage = StorageManager(k.dao, k.root, k.settings, k.finished, k.playing, FreeSpace { Long.MAX_VALUE }, onBookRemoved = { b -> if (b.source == "torrent") engine.remove(b.releaseHash, true) })
        k.seed("T", listOf(10), source = "torrent")
        storage.deleteBook("u1", "T")
        assertEquals(listOf("b".repeat(40) to true), engine.removed)
    }

    @Test fun withoutAListTheMetadataIsFetchedAndMatchedToTheBook() = runBlocking<Unit> {
        val engine = FakeEngine { req -> write(req, "x/1.mp3"); listOf(TorrentProgress.Finished(setOf("x/1.mp3"))) }
        engine.listing = listOf(TorrentFileInfo(0, "x/1.mp3", 4_000), TorrentFileInfo(1, "x/cover.jpg", 10))
        server.onJson("POST", "/v1/releases/$hash/torrent-files", """{"torrent_files":[]}""")
        val k = kit(engine)
        k.repository.enqueue(book, sampleRelease(hash, size = 4_000, usable = xyz.aresius.audiobooks.core.network.Usable(p2p = true)), DownloadChoice.Torrent)
        assertEquals(RunOutcome.Done, k.runner.run("u1", book.asin))
        assertEquals(listOf("x/1.mp3"), k.dao.files("u1", book.asin).map { it.path })
        assertEquals(1, server.requestsTo("POST", "/v1/releases/$hash/torrent-files").size, "the list is reported to the server for the next phone")
    }
}
