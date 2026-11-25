package xyz.aresius.audiobooks.core.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.TorrentFile
import xyz.aresius.audiobooks.testutil.Audio
import xyz.aresius.audiobooks.testutil.DownloadKit
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.cdn
import xyz.aresius.audiobooks.testutil.sampleBook
import xyz.aresius.audiobooks.testutil.sampleRelease

/** Pause keeps the partial files and Resume continues them with Range; Cancel (delete) still removes everything. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DownloadPauseTest {
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
    }

    private fun part() = File(kit.storage.bookDir(book.asin, hash)!!, "one.mp3.part")

    private suspend fun row() = kit.dao.book("u1", book.asin)!!

    @Test fun pauseMarksTheJobStopsTheWorkAndKeepsTheFiles() = runBlocking<Unit> {
        kit.repository.enqueue(book, release, debrid)
        part().also { it.parentFile!!.mkdirs() }.writeBytes(data.copyOfRange(0, 20_000))
        kit.dao.setBytes("u1", book.asin, hash, "one.mp3", 20_000)
        kit.repository.pause(book.asin)

        assertEquals(JobState.USER_PAUSED, row().state)
        assertEquals(listOf("u1:${book.asin}"), kit.scheduler.cancelled)
        assertTrue(part().exists() && part().length() == 20_000L, "the partial file stays")
        assertEquals(20_000L, kit.dao.files("u1", book.asin).single().bytesDone)
        val state = kit.repository.state(book.asin).first()
        assertEquals(BookDownloadState.Paused(WaitReason.User, 20_000, data.size.toLong()), state)
        assertTrue(state.isActive, "a paused download is still a download: Cancel and Resume are offered")
    }

    @Test fun aPausedJobDoesNotRunAndIsNeverReEnqueuedByTheHousekeeping() = runBlocking<Unit> {
        serve()
        kit.repository.enqueue(book, release, debrid)
        kit.repository.pause(book.asin)
        assertEquals(RunOutcome.Paused, kit.runner.run("u1", book.asin))
        assertTrue(server.requests.isEmpty() && cdn.requestCount == 0, "nothing is requested while paused")
        kit.scheduler.enqueued.clear()
        DownloadMaintenance(kit.dao, kit.storage, kit.sessions, kit.settings, kit.scheduler).reconcile("u1")
        assertTrue(kit.scheduler.enqueued.isEmpty(), "reconcile after a restart leaves it paused")
    }

    @Test fun pauseWinsOverTheStateWritesOfARunningJob() = runBlocking<Unit> {
        serve()
        kit.repository.enqueue(book, release, debrid)
        kit.sourceOverride = object : DownloadSource {
            override suspend fun run(ctx: JobContext) {
                ctx.setDownloading()
                kit.repository.pause(book.asin)
                ctx.setDownloading() // the worker has not noticed yet: its next write must not undo the pause
                error("not reached")
            }
        }
        assertEquals(RunOutcome.Paused, kit.runner.run("u1", book.asin))
        assertEquals(JobState.USER_PAUSED, row().state)
    }

    @Test fun aFailureAfterPauseDoesNotTurnIntoFailed() = runBlocking<Unit> {
        kit.repository.enqueue(book, release, debrid)
        kit.sourceOverride = object : DownloadSource {
            override suspend fun run(ctx: JobContext) {
                kit.repository.pause(book.asin)
                throw DownloadException(FailReason.Network, "late failure", retryable = false)
            }
        }
        assertEquals(RunOutcome.Failed, kit.runner.run("u1", book.asin))
        assertEquals(JobState.USER_PAUSED, row().state, "the row stays paused whatever the cancelled run reports")
    }

    @Test fun resumeContinuesThePartialFileWithRangeAndFinishes() = runBlocking<Unit> {
        serve()
        kit.repository.enqueue(book, release, debrid)
        part().also { it.parentFile!!.mkdirs() }.writeBytes(data.copyOfRange(0, 20_000))
        kit.repository.pause(book.asin)
        kit.scheduler.enqueued.clear()

        kit.repository.resume(book.asin)
        assertEquals(JobState.QUEUED, row().state)
        val call = kit.scheduler.enqueued.single()
        assertTrue(call.replace && call.asin == book.asin, "the work is scheduled again")

        cdn.enqueue(Audio.response(data.copyOfRange(20_000, data.size), code = 206, headers = arrayOf("Content-Range" to "bytes 20000-${data.size - 1}/${data.size}")))
        assertEquals(RunOutcome.Done, kit.runner.run("u1", book.asin))
        assertEquals("bytes=20000-", cdn.takeRequest().headers["Range"])
        assertContentEquals(data, File(kit.storage.bookDir(book.asin, hash)!!, "one.mp3").readBytes())
        assertFalse(part().exists())
        assertTrue(kit.repository.state(book.asin).first().isCompleted)
    }

    @Test fun resumeDoesNothingForAJobThatIsNotPausedAndCancelStillDeletesAPausedOne() = runBlocking<Unit> {
        serve()
        kit.repository.enqueue(book, release, debrid)
        kit.scheduler.enqueued.clear()
        kit.repository.resume(book.asin)
        assertTrue(kit.scheduler.enqueued.isEmpty())

        part().also { it.parentFile!!.mkdirs() }.writeBytes(data.copyOfRange(0, 1000))
        kit.repository.pause(book.asin)
        kit.repository.delete(book.asin)
        assertEquals(null, kit.dao.book("u1", book.asin))
        assertFalse(part().exists(), "Cancel removes the partial download")
    }

    @Test fun pausingAFinishedOrMissingDownloadChangesNothing() = runBlocking<Unit> {
        kit.seed("DONE")
        kit.repository.pause("DONE")
        kit.repository.pause("NOPE")
        assertEquals(JobState.COMPLETED, kit.dao.book("u1", "DONE")!!.state)
        assertTrue(kit.scheduler.cancelled.isEmpty())
    }
}
