package xyz.aresius.audiobooks.core.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DownloadWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = FakeServer(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build())
    private val app = TestApp(server)
    private val cdn = cdn()
    private val kit = DownloadKit(context, app)
    private val hash = "a".repeat(40)
    private val book = sampleBook()
    private val data = Audio.bytes("w", 30_000)
    private val release = sampleRelease(hash, files = listOf(TorrentFile(0, "w.mp3", data.size.toLong())))
    private val deps = object : DownloadWorkerDeps {
        override val runner = kit.runner
        override val bus = kit.bus
        override val notifications = DownloadNotifications(context)
        override val scheduler = kit.scheduler
        override val maintenance = DownloadMaintenance(kit.dao, kit.storage, kit.sessions, kit.settings, kit.scheduler)
    }

    @AfterTest fun tearDown() {
        kit.close()
        cdn.close()
        server.close()
    }

    private fun worker(asin: String = book.asin, attempt: Int = 0) = TestListenableWorkerBuilder<BookDownloadWorker>(context, DownloadWork.input("u1", asin))
        .setRunAttemptCount(attempt)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                BookDownloadWorker(appContext, workerParameters, deps)
        })
        .build()

    private fun serve() {
        server.onJson("POST", "/v1/releases/$hash/prepare", """{"state":"ready","files":[{"index":0,"path":"w.mp3","size":${data.size}}]}""")
        server.on("GET", "/v1/releases/$hash/files/0/download", MockResponse.Builder().code(302).addHeader("Location", cdn.url("/f").toString()).build())
    }

    private suspend fun enqueue() = kit.repository.enqueue(book, release, DownloadChoice.Debrid(Provider.TorBox))

    @Test fun aFinishedDownloadSucceeds() = runBlocking<Unit> {
        serve()
        cdn.enqueue(Audio.response(data))
        enqueue()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
    }

    @Test fun transientTroubleRetriesWithBackoffAndTheCountResetsWithProgress() = runBlocking<Unit> {
        serve()
        enqueue()
        cdn.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(ListenableWorker.Result.retry(), worker(attempt = 0).doWork())
        assertEquals(1, kit.dao.book("u1", book.asin)!!.attempts)
        cdn.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(ListenableWorker.Result.retry(), worker(attempt = 1).doWork())
        assertEquals(2, kit.dao.book("u1", book.asin)!!.attempts)
        cdn.enqueue(Audio.response(data))
        assertEquals(ListenableWorker.Result.success(), worker(attempt = 2).doWork())
    }

    @Test fun afterTooManyFailuresTheWorkerGivesUp() = runBlocking<Unit> {
        serve()
        enqueue()
        repeat(DownloadRunner.MAX_ATTEMPTS) { cdn.enqueue(MockResponse.Builder().code(503).build()) }
        repeat(DownloadRunner.MAX_ATTEMPTS - 1) { assertEquals(ListenableWorker.Result.retry(), worker(attempt = it).doWork()) }
        assertEquals(ListenableWorker.Result.failure(), worker(attempt = DownloadRunner.MAX_ATTEMPTS).doWork())
        assertEquals(JobState.FAILED, kit.dao.book("u1", book.asin)!!.state)
    }

    @Test fun waitingForWifiAsksForARetryAndAUserErrorFails() = runBlocking<Unit> {
        serve()
        enqueue()
        kit.network.set(NetKind.Metered)
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        kit.network.set(NetKind.Unmetered)
        server.on("POST", "/v1/releases/$hash/prepare", FakeServer.error(403, "no_debrid_key"))
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
    }

    @Test fun aDeletedBookEndsTheWorkWithoutError() = runBlocking<Unit> {
        assertEquals(ListenableWorker.Result.failure(), worker("GONE").doWork())
        assertEquals(ListenableWorker.Result.failure(), TestListenableWorkerBuilder<BookDownloadWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    BookDownloadWorker(appContext, workerParameters, deps)
            }).build().doWork(), "missing input")
    }

    @Test fun theMaintenanceWorkerCleansUp() = runBlocking<Unit> {
        kit.seed("A", listOf(10))
        val orphan = java.io.File(java.io.File(kit.root, "A"), "b".repeat(40) + "/stray.mp3").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(3)) }
        kit.now = System.currentTimeMillis() + 5_000
        val result = TestListenableWorkerBuilder<MaintenanceWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    MaintenanceWorker(appContext, workerParameters, deps)
            }).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(!orphan.exists())
    }

    @Test fun maintenanceBringsActiveJobsBackToLifeAfterARestart() = runBlocking<Unit> {
        kit.seed("Q", listOf(10), state = JobState.QUEUED, complete = false)
        kit.seed("D", listOf(10))
        deps.maintenance.reconcile("u1")
        assertEquals(listOf("Q"), kit.scheduler.enqueued.map { it.asin })
        assertEquals(false, kit.scheduler.enqueued.single().replace, "an existing worker is kept")
    }

    @Test fun aChangedNetworkPolicyRestartsTheActiveWorkersAndAQuotaTimeoutRequeuesNonExpedited() = runBlocking<Unit> {
        kit.seed("Q", listOf(10), state = JobState.DOWNLOADING, complete = false)
        kit.settings.setAllowMobileData(true)
        deps.maintenance.rescheduleActive("u1")
        val call = kit.scheduler.enqueued.single()
        assertTrue(call.replace && call.allowMobile)
        deps.maintenance.requeue("u1", "Q", expedited = false)
        assertEquals(false, kit.scheduler.enqueued.last().expedited)
        deps.maintenance.requeue("u1", "NOPE", expedited = false)
        assertEquals(2, kit.scheduler.enqueued.size, "nothing to requeue for a book without a job")
    }

    @Test fun aLongWaitBeforeARunDoesNotCountAsTimeSpentPreparing() = runBlocking<Unit> {
        serve()
        enqueue()
        kit.dao.updateBook("u1", book.asin) { it.copy(preparingSinceMs = kit.now - 24 * 3_600_000L) }
        cdn.enqueue(Audio.response(data))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
    }

    // --- the real WorkManager scheduler -----------------------------------------------------------------------------------

    @Test fun theSchedulerBuildsUniqueConstrainedWork() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val scheduler = WorkManagerScheduler(context)
        val wm = WorkManager.getInstance(context)
        val name = DownloadWork.uniqueName("u1", book.asin)

        scheduler.enqueue("u1", book.asin, unmeteredOnly = false, allowMobileData = false, replace = false)
        val first = wm.getWorkInfosForUniqueWork(name).get().single()
        assertEquals(NetworkType.UNMETERED, first.constraints.requiredNetworkType)
        assertTrue(DownloadWork.TAG in first.tags)

        scheduler.enqueue("u1", book.asin, unmeteredOnly = false, allowMobileData = false, replace = false)
        assertEquals(first.id, wm.getWorkInfosForUniqueWork(name).get().single().id, "KEEP: the running work is not touched")

        scheduler.enqueue("u1", book.asin, unmeteredOnly = false, allowMobileData = true, replace = true)
        val replaced = wm.getWorkInfosForUniqueWork(name).get().first { it.state != WorkInfo.State.CANCELLED }
        assertNotEquals(first.id, replaced.id)
        assertEquals(NetworkType.CONNECTED, replaced.constraints.requiredNetworkType, "mobile data allowed: any connection")

        scheduler.cancel("u1", book.asin)
        assertTrue(wm.getWorkInfosForUniqueWork(name).get().all { it.state == WorkInfo.State.CANCELLED })

        scheduler.ensureMaintenanceScheduled()
        scheduler.ensureMaintenanceScheduled()
        assertEquals(1, wm.getWorkInfosForUniqueWork(DownloadWork.MAINTENANCE_NAME).get().size)
    }
}
