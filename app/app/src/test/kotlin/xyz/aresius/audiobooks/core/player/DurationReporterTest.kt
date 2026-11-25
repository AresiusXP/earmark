package xyz.aresius.audiobooks.core.player

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.aresius.audiobooks.testutil.FakeServer

class DurationReporterTest {
    private val server = FakeServer()
    private val dao = FakePlayerDao()
    private val scheduler = Counter()
    private val reporter = DurationReporter(dao, { server.api }, scheduler)
    private val path = "/v1/releases/h1/durations"

    @AfterTest fun tearDown() = server.close()

    private suspend fun learned(hash: String, file: String, ms: Long, reported: Boolean = false) = dao.putDuration(FileDurationEntity(hash, file, ms, reported))

    @Test fun oneRequestPerReleaseCarriesEverythingPendingAndEachFileGoesOutOnce() = runBlocking<Unit> {
        learned("h1", "B/1.mp3", 61_000)
        learned("h1", "B/2.mp3", 62_000)
        learned("h2", "C/1.mp3", 5_000)
        server.onJson("POST", path, """{"durations":[{"path":"B/1.mp3","duration_ms":61000},{"path":"B/2.mp3","duration_ms":62000}],"ignored":0}""")
        server.onJson("POST", "/v1/releases/h2/durations", """{"durations":[{"path":"C/1.mp3","duration_ms":5000}],"ignored":0}""")
        assertTrue(reporter.flush())
        assertEquals("""{"durations":[{"path":"B/1.mp3","duration_ms":61000},{"path":"B/2.mp3","duration_ms":62000}]}""", server.lastBody("POST", path))
        assertEquals(1, server.requestsTo("POST", path).size)
        assertEquals(1, server.requestsTo("POST", "/v1/releases/h2/durations").size)
        assertTrue(dao.unreportedDurations().isEmpty())
        assertTrue(reporter.flush())
        assertEquals(1, server.requestsTo("POST", path).size, "reported once, never again")
        assertEquals(0, scheduler.scheduled)
    }

    @Test fun whatTheServerAlreadyHadOrIgnoredIsNotRetried() = runBlocking<Unit> {
        learned("h1", "B/1.mp3", 61_000)
        learned("h1", "B/not-listed.mp3", 9_000)
        server.onJson("POST", path, """{"durations":[{"path":"B/1.mp3","duration_ms":60000}],"ignored":1}""")
        assertTrue(reporter.flush())
        assertTrue(dao.unreportedDurations().isEmpty(), "first writer wins on the server: the stored value is what everybody sees, nothing to resend")
    }

    @Test fun offlineOrBusyKeepsTheRowsAndAsksForALaterRetry() = runBlocking<Unit> {
        learned("h1", "B/1.mp3", 61_000)
        for (failure in listOf(FakeServer.error(503, "upstream_failed"), FakeServer.error(429, "rate_limited"), FakeServer.error(500, "upstream_failed"))) {
            server.on("POST", path, failure)
            assertFalse(reporter.flush())
        }
        assertEquals(1, dao.unreportedDurations().size)
        assertEquals(3, scheduler.scheduled)
        server.close()
        assertFalse(reporter.flush(), "no network")
        assertEquals(1, dao.unreportedDurations().size)
    }

    @Test fun laterTheSameRowsGoOut() = runBlocking<Unit> {
        learned("h1", "B/1.mp3", 61_000)
        server.on("POST", path, FakeServer.error(503, "upstream_failed"))
        assertFalse(reporter.flush())
        server.onJson("POST", path, """{"durations":[{"path":"B/1.mp3","duration_ms":61000}],"ignored":0}""")
        assertTrue(reporter.flush())
        assertTrue(dao.unreportedDurations().isEmpty())
    }

    @Test fun anAnswerThatWillNeverChangeDropsTheRows() = runBlocking<Unit> {
        learned("h1", "B/1.mp3", 61_000)
        server.on("POST", path, FakeServer.error(404, "not_found"))
        assertTrue(reporter.flush())
        assertTrue(dao.unreportedDurations().isEmpty())
        learned("h1", "B/2.mp3", 61_000)
        server.on("POST", path, FakeServer.error(400, "invalid_request", "bad"))
        assertTrue(reporter.flush())
        assertTrue(dao.unreportedDurations().isEmpty())
        assertEquals(0, scheduler.scheduled)
    }

    @Test fun lengthsOutsideTheContractAreNeverSent() = runBlocking<Unit> {
        learned("h1", "B/short.mp3", 500)
        learned("h1", "B/huge.mp3", 172_800_001)
        assertTrue(reporter.flush())
        assertTrue(server.requestsTo("POST", path).isEmpty())
        assertTrue(dao.unreportedDurations().isEmpty())
    }

    @Test fun reportedRowsAndRowsTheServerKnewAreSkipped() = runBlocking<Unit> {
        learned("h1", "B/1.mp3", 61_000, reported = true)
        assertTrue(reporter.flush())
        assertTrue(server.requests.isEmpty())
    }

    @Test fun moreThanTheContractAllowsIsSplitIntoRequests() = runBlocking<Unit> {
        repeat(DurationReporter.MAX_ENTRIES + 5) { learned("h1", "B/%05d.mp3".format(it), 61_000) }
        server.onJson("POST", path, """{"durations":[],"ignored":0}""")
        assertTrue(reporter.flush())
        assertEquals(2, server.requestsTo("POST", path).size)
        assertTrue(dao.unreportedDurations().isEmpty())
    }

    @Test fun signedOutIsNotAFailureToRetryForever() = runBlocking<Unit> {
        learned("h1", "B/1.mp3", 61_000)
        val signedOut = DurationReporter(dao, { throw xyz.aresius.audiobooks.core.network.NotSignedInException() }, scheduler)
        assertTrue(signedOut.flush())
        assertEquals(0, scheduler.scheduled)
        assertEquals(1, dao.unreportedDurations().size)
    }
}
