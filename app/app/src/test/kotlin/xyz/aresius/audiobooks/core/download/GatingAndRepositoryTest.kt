package xyz.aresius.audiobooks.core.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.aresius.audiobooks.core.data.SessionCleanup
import xyz.aresius.audiobooks.core.data.SessionWipeHook
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.TorrentFile
import xyz.aresius.audiobooks.core.network.Usable
import xyz.aresius.audiobooks.testutil.Audio
import xyz.aresius.audiobooks.testutil.DownloadKit
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.cdn
import xyz.aresius.audiobooks.testutil.sampleBook
import xyz.aresius.audiobooks.testutil.sampleRelease

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GatingAndRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = FakeServer(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build())
    private val app = TestApp(server)
    private val cdn = cdn()
    private val kit = DownloadKit(context, app)
    private val hash = "a".repeat(40)
    private val book = sampleBook()
    private val data = Audio.bytes("only", 50_000)
    private val release = sampleRelease(hash, files = listOf(TorrentFile(0, "one.mp3", data.size.toLong())))
    private val debrid = DownloadChoice.Debrid(Provider.TorBox)

    @AfterTest fun tearDown() {
        kit.close()
        cdn.close()
        server.close()
    }

    private fun serve() {
        server.onJson("POST", "/v1/releases/$hash/prepare", """{"state":"ready","files":[{"index":0,"path":"one.mp3","size":${data.size}}]}""")
        server.on("GET", "/v1/releases/$hash/files/0/download", MockResponse.Builder().code(302).addHeader("Location", cdn.url("/f").toString()).build())
        cdn.enqueue(Audio.response(data))
    }

    // --- Wi-Fi only (default) / mobile data -------------------------------------------------------------------------------

    @Test fun theGateAllowsWifiAlwaysAndMobileDataOnlyWhenTheUserAllowsIt() {
        assertEquals(Gate.Go, DownloadGate.check(NetKind.Unmetered, allowMobileData = false))
        assertEquals(Gate.Wait(WaitReason.WiFi), DownloadGate.check(NetKind.Metered, allowMobileData = false))
        assertEquals(Gate.Go, DownloadGate.check(NetKind.Metered, allowMobileData = true))
        assertEquals(Gate.Wait(WaitReason.WiFi), DownloadGate.check(NetKind.Metered, allowMobileData = true, unmeteredOnly = true), "auto-download stays on Wi-Fi")
        assertEquals(Gate.Wait(WaitReason.Network), DownloadGate.check(NetKind.None, allowMobileData = true))
        assertEquals(DownloadPrefs().allowMobileData, false, "Wi-Fi only is the default")
    }

    @Test fun workManagerGetsTheMatchingNetworkConstraint() {
        assertEquals(androidx.work.NetworkType.UNMETERED, DownloadWork.networkType(unmeteredOnly = false, allowMobileData = false))
        assertEquals(androidx.work.NetworkType.CONNECTED, DownloadWork.networkType(unmeteredOnly = false, allowMobileData = true))
        assertEquals(androidx.work.NetworkType.UNMETERED, DownloadWork.networkType(unmeteredOnly = true, allowMobileData = true))
    }

    @Test fun onMobileDataADownloadWaitsAndTouchesNothing() = runBlocking<Unit> {
        serve()
        kit.repository.enqueue(book, release, debrid)
        assertEquals(false, kit.scheduler.enqueued.single().allowMobile)
        kit.network.set(NetKind.Metered)
        assertEquals(RunOutcome.Waiting(WaitReason.WiFi), kit.runner.run("u1", book.asin))
        assertTrue(server.requests.isEmpty() && cdn.requestCount == 0, "no request is made while waiting for Wi-Fi")
        assertEquals(BookDownloadState.Paused(WaitReason.WiFi, 0, data.size.toLong()), kit.repository.state(book.asin).first { it is BookDownloadState.Paused })

        kit.network.set(NetKind.None)
        assertEquals(RunOutcome.Waiting(WaitReason.Network), kit.runner.run("u1", book.asin))

        kit.network.set(NetKind.Unmetered)
        assertEquals(RunOutcome.Done, kit.runner.run("u1", book.asin))
    }

    @Test fun allowingMobileDataLetsTheDownloadRunOnMobileData() = runBlocking<Unit> {
        serve()
        kit.settings.setAllowMobileData(true)
        kit.repository.enqueue(book, release, debrid)
        assertEquals(true, kit.scheduler.enqueued.single().allowMobile)
        kit.network.set(NetKind.Metered)
        assertEquals(RunOutcome.Done, kit.runner.run("u1", book.asin))
    }

    @Test fun theStateTurnsIntoPausedWhenTheNetworkOrTheSettingChanges() = runBlocking<Unit> {
        kit.repository.enqueue(book, release, debrid)
        assertEquals(BookDownloadState.Queued, kit.repository.state(book.asin).first { it !is BookDownloadState.None })
        kit.network.set(NetKind.Metered)
        assertIs<BookDownloadState.Paused>(kit.repository.state(book.asin).first { it is BookDownloadState.Paused })
        kit.settings.setAllowMobileData(true)
        assertEquals(BookDownloadState.Queued, kit.repository.state(book.asin).first { it is BookDownloadState.Queued })
    }

    @Test fun autoDownloadIsWifiOnlyAndOffByDefault() = runBlocking<Unit> {
        assertNull(kit.repository.autoDownloadIfEnabled(book, release))
        kit.settings.setAutoDownloadWhileListening(true)
        kit.settings.setAllowMobileData(true)
        assertEquals(EnqueueResult.Started, kit.repository.autoDownloadIfEnabled(book, release))
        assertEquals(true, kit.dao.book("u1", book.asin)!!.unmeteredOnly)
        kit.network.set(NetKind.Metered)
        assertIs<BookDownloadState.Paused>(kit.repository.state(book.asin).first { it is BookDownloadState.Paused }, "even with mobile data allowed")
        assertNull(kit.repository.autoDownloadIfEnabled(book, release), "never touches a book that already has a job")
    }

    // --- repository -------------------------------------------------------------------------------------------------------

    @Test fun enqueueRecordsTheRowsAndSchedulesTheWork() = runBlocking<Unit> {
        assertEquals(EnqueueResult.Started, kit.repository.enqueue(book, release, debrid))
        assertEquals("u1", kit.scheduler.enqueued.single().userId)
        val files = kit.dao.files("u1", book.asin)
        assertEquals(listOf("one.mp3"), files.map { it.path })
        assertEquals(JobState.QUEUED, kit.dao.book("u1", book.asin)!!.state)
        assertEquals(EnqueueResult.AlreadyActive, kit.repository.enqueue(book, release, debrid))
    }

    @Test fun enqueueNeedsAKeyOrBitTorrentAndAnEngine() = runBlocking<Unit> {
        assertEquals(EnqueueResult.NeedsKey, kit.repository.enqueue(book, release, DownloadChoices.choose(sampleRelease(usable = Usable()))))
        assertEquals(EnqueueResult.Rejected(FailReason.TorrentUnavailable), kit.repository.enqueue(book, release, DownloadChoice.Torrent), "no engine bound")
        assertEquals(DownloadChoice.Torrent, DownloadChoices.choose(sampleRelease(usable = Usable(p2p = true))))
        assertEquals(DownloadChoice.Debrid(Provider.TorBox), DownloadChoices.choose(sampleRelease(usable = Usable(debrid = listOf("realdebrid", "torbox"), p2p = true))), "cached at TorBox wins")
    }

    @Test fun anotherReleaseReplacesTheOldDownload() = runBlocking<Unit> {
        kit.seed(book.asin, listOf(100), hash = "c".repeat(40))
        assertEquals(EnqueueResult.Started, kit.repository.enqueue(book, release, debrid))
        assertEquals(hash, kit.dao.book("u1", book.asin)!!.releaseHash)
        assertEquals(1, kit.dao.books("u1").size)
    }

    @Test fun aFinishedDownloadIsNotStartedAgainAndAFailedOneKeepsItsCompleteFiles() = runBlocking<Unit> {
        serve()
        kit.repository.enqueue(book, release, debrid)
        assertEquals(RunOutcome.Done, kit.runner.run("u1", book.asin))
        assertEquals(EnqueueResult.AlreadyDownloaded, kit.repository.enqueue(book, release, debrid))
    }

    @Test fun retryRestartsAFailedJobAndDeleteRemovesEverything() = runBlocking<Unit> {
        server.onJson("POST", "/v1/releases/$hash/prepare", """{"state":"failed","reason":"no_seeders"}""")
        kit.repository.enqueue(book, release, debrid)
        assertEquals(RunOutcome.Failed, kit.runner.run("u1", book.asin))
        assertEquals(BookDownloadState.Failed(FailReason.NoSeeders, kit.dao.book("u1", book.asin)!!.failMessage, null), kit.repository.state(book.asin).first { it is BookDownloadState.Failed })
        kit.scheduler.enqueued.clear()
        kit.repository.retry(book.asin)
        assertEquals(JobState.QUEUED, kit.dao.book("u1", book.asin)!!.state)
        assertEquals(1, kit.scheduler.enqueued.size)
        kit.repository.delete(book.asin)
        assertNull(kit.dao.book("u1", book.asin))
        assertTrue(kit.dao.files("u1", book.asin).isEmpty())
        assertEquals(listOf("u1:${book.asin}"), kit.scheduler.cancelled)
    }

    @Test fun refusesToStartWhenThereIsNoRoom() = runBlocking<Unit> {
        kit.free = 10
        assertEquals(EnqueueResult.Rejected(FailReason.NoSpace), kit.repository.enqueue(book, release, debrid))
        kit.free = Long.MAX_VALUE / 2
        kit.settings.setStorageLimit(1_000)
        assertEquals(EnqueueResult.Rejected(FailReason.StorageLimit), kit.repository.enqueue(book, release, debrid))
        assertNull(kit.dao.book("u1", book.asin))
    }

    @Test fun deleteAllRemovesEveryBookOfTheUser() = runBlocking<Unit> {
        kit.seed("A", listOf(10)); kit.seed("B", listOf(10), hash = "d".repeat(40)); kit.seed("O", listOf(10), userId = "u2", hash = "e".repeat(40))
        kit.repository.deleteAll()
        assertEquals(emptyList(), kit.dao.books("u1"))
        assertEquals(1, kit.dao.books("u2").size)
    }

    @Test fun theDownloadedBadgeFollowsCompletedBooksOnly() = runBlocking<Unit> {
        kit.seed("DONE", listOf(10)); kit.seed("PART", listOf(10), hash = "d".repeat(40), state = JobState.DOWNLOADING, complete = false)
        assertEquals(setOf("DONE"), kit.repository.downloadedAsins.first())
        assertEquals(2, kit.repository.downloads.first().size)
    }

    // --- sign-out / user switch -----------------------------------------------------------------------------------------

    @Test fun signingOutWipesTheDownloadsThroughTheSessionCleanupHook() = runBlocking<Unit> {
        kit.seed("A", listOf(100))
        var other = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cleanup = SessionCleanup(
            app.sessions, app.cache, app.libraryDao, app.me, app.prefs,
            setOf(SessionWipeHook { error("a hook that fails must not stop the others") }, SessionWipeHook { kit.repository.wipe() }, SessionWipeHook { other++ }),
        )
        cleanup.start(scope)
        app.sessions.clear()
        val end = System.nanoTime() + 3_000_000_000
        while (System.nanoTime() < end && (kit.dao.allBooks().isNotEmpty() || other == 0)) Thread.sleep(10)
        assertTrue(kit.dao.allBooks().isEmpty() && kit.dao.allFiles().isEmpty())
        assertTrue(!kit.root.exists())
        assertEquals(1, other)
        scope.cancel()
    }

    @Test fun aRunnerNeverWorksForAUserWhoIsNoLongerSignedIn() = runBlocking<Unit> {
        kit.seed("A", listOf(100), userId = "u2", state = JobState.QUEUED, complete = false)
        assertEquals(RunOutcome.Gone, kit.runner.run("u2", "A"))
        assertEquals(RunOutcome.Gone, kit.runner.run("u1", "missing"))
    }
}
