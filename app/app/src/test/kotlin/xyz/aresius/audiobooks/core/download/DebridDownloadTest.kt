package xyz.aresius.audiobooks.core.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.aresius.audiobooks.core.data.ReleaseRepository
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.TorrentFile
import xyz.aresius.audiobooks.testutil.Audio
import xyz.aresius.audiobooks.testutil.DownloadKit
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.cdn
import xyz.aresius.audiobooks.testutil.sampleBook
import xyz.aresius.audiobooks.testutil.sampleRelease

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DebridDownloadTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val noRedirects = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private val server = FakeServer(noRedirects)
    private val app = TestApp(server)
    private val cdn = cdn()
    private val kit = DownloadKit(context, app)
    private val book = sampleBook()
    private val hash = "a".repeat(40)
    private val user = "u1"

    private val ch1 = Audio.bytes("ch1", 120_000)
    private val ch2 = Audio.bytes("ch2", 90_000)

    // The release lists the files with indexes of its own; the provider's indexes (in prepare) differ: files are matched by PATH.
    private val listed = listOf(TorrentFile(7, "Book/01 Intro.mp3", ch1.size.toLong()), TorrentFile(8, "Book/02 End.mp3", ch2.size.toLong()))
    private val release = sampleRelease(hash, files = listed)

    @AfterTest fun tearDown() {
        kit.close()
        cdn.close()
        server.close()
    }

    private fun prepareReady(vararg extra: TorrentFile) {
        val files = listOf(TorrentFile(1, "/Book/01 Intro.mp3", ch1.size.toLong()), TorrentFile(2, "Book/02 End.mp3", ch2.size.toLong()), TorrentFile(3, "Book/cover.jpg", 500)) + extra
        server.onJson("POST", "/v1/releases/$hash/prepare", """{"state":"ready","files":[${files.joinToString(",") { """{"index":${it.index},"path":"${it.path}","size":${it.size}}""" }}]}""")
    }

    private fun redirect(index: Int, path: String) =
        server.on("GET", "/v1/releases/$hash/files/$index/download", MockResponse.Builder().code(302).addHeader("Location", cdn.url(path).toString()).build())

    private suspend fun start(r: xyz.aresius.audiobooks.core.network.ReleaseView = release) =
        kit.repository.enqueue(book, r, DownloadChoice.Debrid(Provider.TorBox))

    private fun booksDir() = File(File(kit.root, book.asin), hash)

    private fun job() = runBlocking { kit.dao.book(user, book.asin)!! }

    private fun failure(): BookDownloadState.Failed {
        val j = job()
        return BookDownloadState.Failed(FailReason.fromWire(j.failReason)!!, j.failMessage, j.retryAfterSec)
    }

    @Test fun downloadsTheFilesOfTheBookInOrderMatchedByPath() = runBlocking<Unit> {
        prepareReady()
        redirect(1, "/cdn/1")
        redirect(2, "/cdn/2")
        cdn.enqueue(Audio.response(ch1))
        cdn.enqueue(Audio.response(ch2))
        assertEquals(EnqueueResult.Started, start())
        assertEquals(RunOutcome.Done, kit.runner.run(user, book.asin))

        assertContentEquals(ch1, File(booksDir(), "Book/01 Intro.mp3").readBytes())
        assertContentEquals(ch2, File(booksDir(), "Book/02 End.mp3").readBytes())
        assertEquals(setOf("Book/01 Intro.mp3", "Book/02 End.mp3"), kit.dao.files(user, book.asin).filter { it.state == FileState.COMPLETE }.map { it.path }.toSet())
        assertEquals(listOf(1, 2), kit.dao.files(user, book.asin).map { it.providerIndex }, "the provider's index is used for the route, not the release's")
        assertEquals(JobState.COMPLETED, job().state)
        assertTrue(server.requestsTo("GET", "/v1/releases/$hash/files/7/download").isEmpty())
        assertEquals("torbox", server.requestsTo("GET", "/v1/releases/$hash/files/1/download").single().url.queryParameter("provider"))
        assertNull(File(booksDir(), "Book/cover.jpg").takeIf { it.exists() }, "files that are not in the book are never fetched")
        assertEquals(2, cdn.requestCount)
        assertEquals(BookDownloadState.Completed(ch1.size + ch2.size.toLong()), kit.repository.state(book.asin).let { f -> f.first { it is BookDownloadState.Completed } })
    }

    @Test fun preparingIsPolledUntilReady() = runBlocking<Unit> {
        server.on(
            "POST", "/v1/releases/$hash/prepare",
            FakeServer.json("""{"state":"preparing","progress":30,"retry_after_s":3}"""),
            FakeServer.json("""{"state":"ready","files":[{"index":1,"path":"Book/01 Intro.mp3","size":${ch1.size}},{"index":2,"path":"Book/02 End.mp3","size":${ch2.size}}]}"""),
        )
        redirect(1, "/cdn/1")
        redirect(2, "/cdn/2")
        cdn.enqueue(Audio.response(ch1))
        cdn.enqueue(Audio.response(ch2))
        start()
        assertEquals(RunOutcome.Done, kit.runner.run(user, book.asin))
        assertEquals(2, server.requestsTo("POST", "/v1/releases/$hash/prepare").size)
    }

    @Test fun a202OnTheDownloadRouteIsNeverSavedAsAudio() = runBlocking<Unit> {
        prepareReady()
        server.on(
            "GET", "/v1/releases/$hash/files/1/download",
            FakeServer.json("""{"state":"preparing","progress":5,"retry_after_s":2}""", 202),
            MockResponse.Builder().code(302).addHeader("Location", cdn.url("/cdn/1").toString()).build(),
        )
        redirect(2, "/cdn/2")
        cdn.enqueue(Audio.response(ch1))
        cdn.enqueue(Audio.response(ch2))
        start()
        assertEquals(RunOutcome.Done, kit.runner.run(user, book.asin))
        assertEquals(2, server.requestsTo("GET", "/v1/releases/$hash/files/1/download").size, "asked again after the 202")
        assertEquals(2, cdn.requestCount, "the CDN was only called once per file: the placeholder never reached it")
        assertContentEquals(ch1, File(booksDir(), "Book/01 Intro.mp3").readBytes())
    }

    @Test fun aCdnAnswerThatIsNotAudioIsNeverSaved() = runBlocking<Unit> {
        prepareReady()
        redirect(1, "/cdn/1")
        cdn.enqueue(Audio.response("""{"error":"expired"}""".toByteArray(), type = "application/json"))
        cdn.enqueue(Audio.response("<html>".toByteArray(), type = "text/html"))
        cdn.enqueue(Audio.response("{}".toByteArray(), type = "application/json"))
        start()
        assertEquals(RunOutcome.Failed, kit.runner.run(user, book.asin))
        assertEquals(FailReason.InvalidContent, failure().reason)
        assertEquals(3, cdn.requestCount)
        assertFalse(File(booksDir(), "Book/01 Intro.mp3").exists())
        assertFalse(File(booksDir(), "Book/01 Intro.mp3.part").exists())
        assertTrue(kit.dao.files(user, book.asin).none { it.state == FileState.COMPLETE })
    }

    @Test fun aWrongSizeFailsTheIntegrityCheck() = runBlocking<Unit> {
        prepareReady()
        redirect(1, "/cdn/1")
        repeat(3) { cdn.enqueue(Audio.response(Audio.bytes("short", 5_000))) }
        start()
        assertEquals(RunOutcome.Failed, kit.runner.run(user, book.asin))
        assertEquals(FailReason.Integrity, failure().reason)
        assertFalse(File(booksDir(), "Book/01 Intro.mp3").exists())
    }

    @Test fun resumesAPartialFileWithRange() = runBlocking<Unit> {
        prepareReady()
        redirect(1, "/cdn/1")
        redirect(2, "/cdn/2")
        start()
        val part = File(booksDir(), "Book/01 Intro.mp3.part")
        part.parentFile!!.mkdirs()
        part.writeBytes(ch1.copyOfRange(0, 50_000))
        cdn.enqueue(Audio.response(ch1.copyOfRange(50_000, ch1.size), code = 206, headers = arrayOf("Content-Range" to "bytes 50000-${ch1.size - 1}/${ch1.size}")))
        cdn.enqueue(Audio.response(ch2))
        assertEquals(RunOutcome.Done, kit.runner.run(user, book.asin))
        assertEquals("bytes=50000-", cdn.takeRequest().headers["Range"])
        assertContentEquals(ch1, File(booksDir(), "Book/01 Intro.mp3").readBytes())
        assertFalse(part.exists())
    }

    @Test fun theAuthorizationHeaderGoesToTheServerOnlyNeverToTheCdn() = runBlocking<Unit> {
        val real = DownloadKit(context, app, releases = ReleaseRepository(app.provider), cdnClient = OkHttpClient())
        try {
            prepareReady()
            redirect(1, "/cdn/1")
            redirect(2, "/cdn/2")
            cdn.enqueue(Audio.response(ch1))
            cdn.enqueue(Audio.response(ch2))
            real.repository.enqueue(book, release, DownloadChoice.Debrid(Provider.TorBox))
            assertEquals(RunOutcome.Done, real.runner.run(user, book.asin))
            assertEquals("Bearer access-1", server.requestsTo("GET", "/v1/releases/$hash/files/1/download").single().headers["Authorization"])
            repeat(cdn.requestCount) { assertNull(cdn.takeRequest().headers["Authorization"], "the CDN must never see our token") }
        } finally {
            real.close()
        }
    }

    @Test fun userFixableErrorsBecomeFailedStatesWithTheirReason() = runBlocking<Unit> {
        val cases = listOf(
            Triple(FakeServer.error(403, "no_debrid_key"), FailReason.NoDebridKey, null),
            Triple(FakeServer.error(403, "bad_debrid_key"), FailReason.BadDebridKey, null),
            Triple(FakeServer.error(429, "rate_limited", "budget", "Retry-After" to "90"), FailReason.RateLimited, 90),
            Triple(FakeServer.error(404, "not_found"), FailReason.NotFound, null),
            Triple(FakeServer.error(502, "upstream_failed", "failed at the provider"), FailReason.DebridFailed, null),
        )
        for ((response, reason, retryAfter) in cases) {
            kit.dao.clearAll()
            server.on("POST", "/v1/releases/$hash/prepare", response)
            start()
            assertEquals(RunOutcome.Failed, kit.runner.run(user, book.asin), reason.name)
            val f = failure()
            assertEquals(reason, f.reason)
            assertEquals(retryAfter, f.retryAfterSec, reason.name)
            assertEquals(0, cdn.requestCount)
        }
    }

    @Test fun theDownloadRouteErrorsMapToo() = runBlocking<Unit> {
        prepareReady()
        server.on("GET", "/v1/releases/$hash/files/1/download", FakeServer.error(429, "rate_limited", null, "Retry-After" to "30"))
        start()
        assertEquals(RunOutcome.Failed, kit.runner.run(user, book.asin))
        assertEquals(FailReason.RateLimited, failure().reason)
        assertEquals(30, failure().retryAfterSec)
    }

    @Test fun prepareFailureReasonsOfTheServerAreKept() = runBlocking<Unit> {
        val reasons = mapOf("no_seeders" to FailReason.NoSeeders, "debrid_cooldown" to FailReason.DebridCooldown, "debrid_busy" to FailReason.DebridBusy, "debrid_failed" to FailReason.DebridFailed, "not_found" to FailReason.NotFound)
        for ((wire, reason) in reasons) {
            kit.dao.clearAll()
            server.onJson("POST", "/v1/releases/$hash/prepare", """{"state":"failed","reason":"$wire"}""")
            start()
            assertEquals(RunOutcome.Failed, kit.runner.run(user, book.asin))
            assertEquals(reason, failure().reason, wire)
        }
    }

    @Test fun aTransientProblemRetriesWithBackoffAndEventuallyFails() = runBlocking<Unit> {
        prepareReady()
        redirect(1, "/cdn/1")
        repeat(DownloadRunner.MAX_ATTEMPTS) { cdn.enqueue(MockResponse.Builder().code(503).body("busy").build()) }
        start()
        repeat(DownloadRunner.MAX_ATTEMPTS - 1) { i ->
            assertEquals(RunOutcome.Retry, kit.runner.run(user, book.asin))
            assertEquals(i + 1, job().attempts)
        }
        assertEquals(RunOutcome.Failed, kit.runner.run(user, book.asin))
        assertEquals(FailReason.Network, failure().reason)
    }

    @Test fun serverPathsThatLeaveTheBookDirectoryAreRejected() = runBlocking<Unit> {
        // 1. in the release's own file list: refused before anything is queued
        val evil = sampleRelease(hash, files = listOf(TorrentFile(0, "../../evil.mp3", 1000)))
        assertEquals(EnqueueResult.Rejected(FailReason.InvalidPath), start(evil))
        assertEquals(EnqueueResult.Rejected(FailReason.InvalidPath), start(sampleRelease(hash, files = listOf(TorrentFile(0, "/sdcard/evil.mp3", 1000)))))
        assertNull(kit.dao.book(user, book.asin))
        // 2. in the provider's answer, when the release lists no files: the job fails and nothing is written
        val unknown = sampleRelease(hash, files = emptyList(), size = 1_000_000)
        server.onJson("POST", "/v1/releases/$hash/prepare", """{"state":"ready","files":[{"index":1,"path":"../../escape.mp3","size":2000}]}""")
        assertEquals(EnqueueResult.Started, start(unknown))
        assertEquals(RunOutcome.Failed, kit.runner.run(user, book.asin))
        assertEquals(FailReason.InvalidPath, failure().reason)
        assertFalse(File(kit.root, "../escape.mp3").exists())
        assertEquals(0, cdn.requestCount)
        assertTrue(kit.dao.files(user, book.asin).isEmpty())
    }

    @Test fun aPackWhoseFilesAreUnknownIsNotDownloaded() = runBlocking<Unit> {
        val pack = sampleRelease(hash, files = emptyList(), collection = true, size = 5_000_000)
        assertEquals(EnqueueResult.Rejected(FailReason.PackFilesUnknown), start(pack))
    }

    @Test fun withoutAFileListTheProvidersAudioFilesAreUsedInNaturalOrder() = runBlocking<Unit> {
        server.onJson(
            "POST", "/v1/releases/$hash/prepare",
            """{"state":"ready","files":[{"index":4,"path":"b/Track 10.mp3","size":${ch2.size}},{"index":3,"path":"b/Track 2.mp3","size":${ch1.size}},{"index":5,"path":"b/info.nfo","size":10}]}""",
        )
        redirect(3, "/cdn/3")
        redirect(4, "/cdn/4")
        cdn.enqueue(Audio.response(ch1))
        cdn.enqueue(Audio.response(ch2))
        start(sampleRelease(hash, files = emptyList(), size = ch1.size + ch2.size.toLong()))
        assertEquals(RunOutcome.Done, kit.runner.run(user, book.asin))
        assertEquals(listOf("b/Track 2.mp3", "b/Track 10.mp3"), kit.dao.files(user, book.asin).map { it.path })
    }

    @Test fun aCompleteFileIsKeptWhenARetryRunsAgain() = runBlocking<Unit> {
        prepareReady()
        redirect(1, "/cdn/1")
        redirect(2, "/cdn/2")
        cdn.enqueue(Audio.response(ch1))
        cdn.enqueue(MockResponse.Builder().code(503).build())
        start()
        assertEquals(RunOutcome.Retry, kit.runner.run(user, book.asin))
        cdn.enqueue(Audio.response(ch2))
        assertEquals(RunOutcome.Done, kit.runner.run(user, book.asin))
        assertEquals(3, cdn.requestCount, "file 1 was not fetched again")
        assertIs<BookDownloadState.Completed>(kit.repository.state(book.asin).let { f -> f.first { it is BookDownloadState.Completed } })
    }
}
