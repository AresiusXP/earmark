package xyz.aresius.audiobooks.core.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ErrorCode
import xyz.aresius.audiobooks.core.network.errorOrNull
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.MemoryLibraryDao
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.TestApp

class LibraryRepositoryTest {
    private val server = FakeServer()
    private val app = TestApp(server)
    private val dao get() = app.memLibrary

    @AfterTest fun tearDown() = server.close()

    private fun library(vararg entries: String) = """{"entries":[${entries.joinToString(",")}]}"""

    @Test fun refreshReplacesTheCachedCopyAndOrdersByRecency() = runBlocking<Unit> {
        server.onJson("GET", "/v1/me/library", library(Samples.libraryEntry("B000000001", updated = 10), Samples.libraryEntry("B000000002", "saved", updated = 20)))
        app.library.refresh()
        assertEquals(listOf("B000000002", "B000000001"), app.library.entries.first().map { it.book.asin })
        assertEquals(600000, app.library.entry("B000000001").first()?.positionMs)
        server.onJson("GET", "/v1/me/library", library(Samples.libraryEntry("B000000003")))
        app.library.refresh()
        assertEquals(listOf("B000000003"), app.library.entries.first().map { it.book.asin })
    }

    @Test fun aFailedRefreshKeepsWhatWasCached() = runBlocking<Unit> {
        server.onJson("GET", "/v1/me/library", library(Samples.libraryEntry("B000000001")))
        app.library.refresh()
        server.on("GET", "/v1/me/library", FakeServer.error(500, "upstream_failed"))
        assertTrue(app.library.refresh().errorOrNull is ApiError.Http)
        assertEquals(1, app.library.entries.first().size)
    }

    @Test fun rowsAreScopedToTheSignedInUser() = runBlocking<Unit> {
        server.onJson("GET", "/v1/me/library", library(Samples.libraryEntry("B000000001")))
        app.library.refresh()
        assertEquals(1, app.library.entries.first().size)
        // Another user on the same phone sees nothing of it.
        app.sessions.save(app.sessions.load()!!.copy(userId = "u2"))
        assertEquals(0, app.library.entries.first().size)
        assertNull(app.library.entry("B000000001").first())
        assertEquals(1, dao.all.size)
    }

    @Test fun addPutsASavedEntryAndStoresTheServersAnswer() = runBlocking<Unit> {
        server.onJson("PUT", "/v1/me/library/B000000001", Samples.libraryEntry("B000000001", "saved", updated = app.now, position = 0))
        val book = AudiobooksJson.decodeFromString(Book.serializer(), Samples.book())
        app.library.add(book)
        val body = server.lastBody("PUT", "/v1/me/library/B000000001")
        assertTrue(body.contains(""""status":"saved""""), body)
        assertTrue(body.contains(""""updated_at":${app.now}"""), body)
        assertEquals("saved", app.library.entry("B000000001").first()?.status)
    }

    @Test fun theLimitIsATypedError() = runBlocking<Unit> {
        server.on("PUT", "/v1/me/library/B000000001", FakeServer.error(409, "limit_reached", "2000 entries"))
        val e = app.library.add(AudiobooksJson.decodeFromString(Book.serializer(), Samples.book())).errorOrNull as ApiError.Http
        assertEquals(ErrorCode.LimitReached, e.code)
    }

    @Test fun removeDeletesOnTheServerThenLocally() = runBlocking<Unit> {
        server.onJson("GET", "/v1/me/library", library(Samples.libraryEntry("B000000001")))
        app.library.refresh()
        server.on("DELETE", "/v1/me/library/B000000001", FakeServer.empty())
        app.library.remove("B000000001")
        assertEquals(0, app.library.entries.first().size)
        // A failure leaves the row in place.
        server.onJson("GET", "/v1/me/library", library(Samples.libraryEntry("B000000001")))
        app.library.refresh()
        server.on("DELETE", "/v1/me/library/B000000001", FakeServer.error(500, "upstream_failed"))
        app.library.remove("B000000001")
        assertEquals(1, app.library.entries.first().size)
    }

    // --- contract v1.1: finished_at ---------------------------------------------------------------------------------

    private val finishedAt = "2026-11-20T10:00:00Z"
    private val finishedAtMs = java.time.Instant.parse(finishedAt).toEpochMilli()

    @Test fun finishedAtComesFromTheServerAndDrivesLruAndAutoDelete() = runBlocking<Unit> {
        // B1 was written last (updated_at 5000) but finished LONG ago; B2 is the other way round; B3 is not finished; B4 is an older server's entry.
        server.onJson(
            "GET", "/v1/me/library",
            library(
                Samples.libraryEntry("B000000001", "finished", updated = 5000, finishedAt = finishedAt),
                Samples.libraryEntry("B000000002", "finished", updated = 1000, finishedAt = "2026-11-24T10:00:00Z"),
                Samples.libraryEntry("B000000003", "listening", updated = 9000),
                Samples.libraryEntry("B000000004", "finished", updated = 1_900_000_000_000),
            ),
        )
        app.library.refresh()
        val userId = app.sessions.load()!!.userId
        val finished = dao.finishedAtMs(userId)
        assertEquals(setOf("B000000001", "B000000002", "B000000004"), finished.keys)
        assertEquals(finishedAtMs, finished["B000000001"], "finished_at beats updated_at")
        assertEquals(1_900_000_000_000L, finished["B000000004"], "no finished_at (older server, or cached before v1.1): the last write")
        assertEquals("B000000001", finished.minByOrNull { it.value }!!.key, "the LRU victim is the book finished longest ago, not the one written longest ago")
        assertEquals(finishedAt, app.library.entry("B000000001").first()?.finishedAt)
        assertNull(app.library.entry("B000000003").first()?.finishedAt)
    }

    @Test fun theLocalFinishKeepsTheCachedTimeUntilTheServerAnswers() = runBlocking<Unit> {
        val userId = app.sessions.load()!!.userId
        val book = Book(asin = "B1", title = "T")
        val listening = xyz.aresius.audiobooks.core.network.LibraryEntry(book, "listening", null, 10, null, 0, 1.0, 1)
        val finished = listening.copy(status = "finished")
        dao.cacheLocalProgress(userId, listening, 100)
        assertNull(dao.get(userId, "B1")?.finishedAtMs)
        dao.cacheLocalProgress(userId, finished, 200)
        assertEquals(200L, dao.get(userId, "B1")?.finishedAtMs, "finishing now")
        dao.cacheLocalProgress(userId, finished.copy(updatedAt = 3), 900)
        assertEquals(200L, dao.get(userId, "B1")?.finishedAtMs, "listening to the end again does not move it")
        dao.cacheLocalProgress(userId, finished.copy(finishedAt = finishedAt), 950)
        assertEquals(finishedAtMs, dao.get(userId, "B1")?.finishedAtMs, "the server's time wins when the entry carries it")
        dao.cacheLocalProgress(userId, listening.copy(updatedAt = 5), 1000)
        assertNull(dao.get(userId, "B1")?.finishedAtMs, "back to listening clears it")
    }
}
