package xyz.aresius.audiobooks.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.memorySessionStore
import xyz.aresius.audiobooks.testutil.testSession

class BookmarkRepositoryTest {
    private val server = FakeServer()
    private val dao = FakePlayerDao()
    private val sessions = memorySessionStore(testSession(server.baseUrl, "u1"))
    private var now = 1_700_000_000_000L
    private var ids = 0
    private val repo = BookmarkRepository(dao, { server.api }, sessions, { "cid-${++ids}" }) { now }

    private fun bookmarkJson(id: String, pos: Long, note: String? = null, clientId: String? = null) =
        """{"id":"$id","position_ms":$pos,${note?.let { """"note":"$it",""" } ?: ""}${clientId?.let { """"client_id":"$it",""" } ?: ""}"created_at":"2026-11-25T10:00:00Z"}"""

    @Test
    fun `add stores locally and mirrors to the server`() = runBlocking {
        server.onJson("POST", "/v1/me/library/B1/bookmarks", bookmarkJson("srv-1", 5000, "nice"))
        val b = repo.add("B1", 5000, "  nice ")!!
        assertEquals("nice", b.note)
        assertTrue(b.synced)
        assertEquals("""{"position_ms":5000,"note":"nice","client_id":"cid-1"}""", server.lastBody("POST", "/v1/me/library/B1/bookmarks"))
        assertEquals(listOf(5000L), repo.observe("B1").first().map { it.positionMs })
    }

    @Test
    fun `offline add stays local and is sent by sync later`() = runBlocking {
        server.on("POST", "/v1/me/library/B1/bookmarks", FakeServer.error(503, "upstream_failed"))
        val b = repo.add("B1", 7000, null)!!
        assertEquals(false, b.synced)
        assertNull(b.note)
        server.onJson("POST", "/v1/me/library/B1/bookmarks", bookmarkJson("srv-2", 7000))
        server.onJson("GET", "/v1/me/library/B1/bookmarks", """{"bookmarks":[${bookmarkJson("srv-2", 7000)}]}""")
        repo.sync("B1")
        val rows = repo.observe("B1").first()
        assertEquals(1, rows.size, "no duplicate after the server list is merged")
        assertTrue(rows.single().synced)
    }

    @Test
    fun `sync merges the server's bookmarks and drops the ones deleted elsewhere`() = runBlocking {
        server.onJson("GET", "/v1/me/library/B1/bookmarks", """{"bookmarks":[${bookmarkJson("s1", 100, "a")},${bookmarkJson("s2", 200)}]}""")
        repo.sync("B1")
        assertEquals(listOf(100L, 200L), repo.observe("B1").first().map { it.positionMs })
        server.onJson("GET", "/v1/me/library/B1/bookmarks", """{"bookmarks":[${bookmarkJson("s2", 200)}]}""")
        repo.sync("B1")
        assertEquals(listOf(200L), repo.observe("B1").first().map { it.positionMs })
    }

    @Test
    fun `a bookmark that the server has not seen yet survives a merge`() = runBlocking {
        server.on("POST", "/v1/me/library/B1/bookmarks", FakeServer.error(503, "upstream_failed"))
        repo.add("B1", 300, "local")
        server.onJson("GET", "/v1/me/library/B1/bookmarks", """{"bookmarks":[]}""")
        repo.sync("B1")
        assertEquals(listOf(300L), repo.observe("B1").first().map { it.positionMs })
    }

    @Test
    fun `delete removes at once and tells the server, or waits until it can`() = runBlocking {
        server.onJson("POST", "/v1/me/library/B1/bookmarks", bookmarkJson("s1", 100))
        val b = repo.add("B1", 100, null)!!
        server.on("DELETE", "/v1/me/library/B1/bookmarks/s1", FakeServer.error(503, "upstream_failed"))
        repo.delete(b.id)
        assertTrue(repo.observe("B1").first().isEmpty())
        assertEquals(1, dao.unsyncedBookmarks("u1").size, "the delete is still owed to the server")
        server.on("DELETE", "/v1/me/library/B1/bookmarks/s1", FakeServer.empty())
        repo.sync(null)
        assertTrue(dao.unsyncedBookmarks("u1").isEmpty())
        assertEquals(2, server.requestsTo("DELETE", "/v1/me/library/B1/bookmarks/s1").size)
    }

    @Test
    fun `bookmarks are scoped by user`() = runBlocking {
        server.on("POST", "/v1/me/library/B1/bookmarks", FakeServer.error(503, "upstream_failed"))
        repo.add("B1", 100, "mine")
        val other = BookmarkRepository(dao, { server.api }, memorySessionStore(testSession(server.baseUrl, "u2"))) { now }
        assertTrue(other.observe("B1").first().isEmpty())
    }

    @Test
    fun `a long note is cut to the contract limit`() = runBlocking {
        server.on("POST", "/v1/me/library/B1/bookmarks", FakeServer.error(503, "upstream_failed"))
        val b = repo.add("B1", 1, "x".repeat(500))!!
        assertEquals(280, b.note!!.length)
    }

    // --- contract v1.1: client_id makes the create idempotent, the server adds the book -----------------------------

    /** A scripted server that, like the real one, answers a repeated `client_id` with the bookmark it already has. */
    private class IdempotentBookmarks(private val server: FakeServer, private val path: String) {
        val stored = LinkedHashMap<String, String>()
        var dropAnswers = 0
        fun install() = server.onRequest("POST", path) { req ->
            val body = req.body?.utf8().orEmpty()
            val cid = Regex(""""client_id":"([^"]+)"""").find(body)?.groupValues?.get(1)
            val json = stored.getOrPut(cid ?: "none-${stored.size}") {
                val pos = Regex(""""position_ms":(\d+)""").find(body)!!.groupValues[1]
                """{"id":"srv-${stored.size + 1}","position_ms":$pos,"created_at":"2026-11-25T10:00:00Z","client_id":"$cid"}"""
            }
            if (dropAnswers > 0) {
                dropAnswers--
                FakeServer.error(500, "upstream_failed")
            } else {
                FakeServer.json(json)
            }
        }
    }

    @Test
    fun `a lost answer is retried with the same client id and never makes a duplicate`() = runBlocking {
        val remote = IdempotentBookmarks(server, "/v1/me/library/B1/bookmarks").also { it.dropAnswers = 1; it.install() }
        val first = repo.add("B1", 5000, null)!!
        assertEquals(false, first.synced, "the answer was lost")
        assertEquals(1, remote.stored.size, "but the server did create it")
        server.onJson("GET", "/v1/me/library/B1/bookmarks", """{"bookmarks":[${bookmarkJson("srv-1", 5000, clientId = "cid-1")}]}""")
        repo.sync("B1")
        val sent = server.requestsTo("POST", "/v1/me/library/B1/bookmarks").map { it.body?.utf8() }
        assertEquals(2, sent.size)
        assertTrue(sent.all { it!!.contains(""""client_id":"cid-1"""") }, "the retry repeats the id: $sent")
        assertEquals(1, remote.stored.size)
        val rows = repo.observe("B1").first()
        assertEquals(1, rows.size)
        assertTrue(rows.single().synced)
    }

    @Test
    fun `every bookmark gets its own client id`() = runBlocking {
        IdempotentBookmarks(server, "/v1/me/library/B1/bookmarks").install()
        repo.add("B1", 1, null)
        repo.add("B1", 2, null)
        val ids = server.requestsTo("POST", "/v1/me/library/B1/bookmarks").map { Regex(""""client_id":"([^"]+)"""").find(it.body!!.utf8())!!.groupValues[1] }
        assertEquals(listOf("cid-1", "cid-2"), ids)
    }

    @Test
    fun `a merge adopts the server's id for a bookmark whose answer never arrived instead of adding it twice`() = runBlocking {
        server.on("POST", "/v1/me/library/B1/bookmarks", FakeServer.error(503, "upstream_failed"))
        repo.add("B1", 5000, "mine")
        server.onJson("GET", "/v1/me/library/B1/bookmarks", """{"bookmarks":[${bookmarkJson("srv-9", 5000, "mine", clientId = "cid-1")},${bookmarkJson("srv-10", 9000, clientId = "from-another-phone")}]}""")
        repo.sync("B1")
        val rows = dao.bookmarks("u1", "B1")
        assertEquals(listOf(5000L, 9000L), rows.map { it.positionMs }.sorted())
        assertEquals(setOf("srv-9", "srv-10"), rows.mapNotNull { it.serverId }.toSet())
        assertEquals("from-another-phone", rows.single { it.serverId == "srv-10" }.clientId)
    }

    @Test
    fun `a bookmark from before client ids gets one stored before it is sent, and keeps it on every retry`() = runBlocking {
        dao.putBookmark(BookmarkEntity(userId = "u1", asin = "B1", serverId = null, positionMs = 300, note = null, createdAtMs = 1, clientId = null))
        server.on("POST", "/v1/me/library/B1/bookmarks", FakeServer.error(503, "upstream_failed"), FakeServer.json(bookmarkJson("s1", 300, clientId = "cid-1")))
        repo.sync(null)
        assertEquals("cid-1", dao.bookmarks("u1", "B1").single().clientId)
        repo.sync(null)
        val sent = server.requestsTo("POST", "/v1/me/library/B1/bookmarks").map { it.body!!.utf8() }
        assertEquals(listOf("""{"position_ms":300,"client_id":"cid-1"}""", """{"position_ms":300,"client_id":"cid-1"}"""), sent)
        assertEquals("s1", dao.bookmarks("u1", "B1").single().serverId)
        assertEquals(1, ids, "no second id was made")
    }

    @Test
    fun `the book not being in the library is no longer a reason to wait, the server adds it`() = runBlocking {
        server.onJson("POST", "/v1/me/library/NEW1/bookmarks", bookmarkJson("s1", 10, clientId = "cid-1"))
        val b = repo.add("NEW1", 10, null)!!
        assertTrue(b.synced, "created on the first request, no library PUT and no second try")
        assertEquals(1, server.requestsTo("POST", "/v1/me/library/NEW1/bookmarks").size)
        assertTrue(server.requestsTo("PUT", "/v1/me/library/NEW1").isEmpty())
    }

    @Test
    fun `an unknown book or an invalid bookmark is final, a full book or a busy server is tried again`() = runBlocking {
        server.on("POST", "/v1/me/library/B1/bookmarks", FakeServer.error(404, "not_found"))
        repo.add("B1", 1, null)
        assertTrue(dao.unsyncedBookmarks("u1").isEmpty(), "404 = not a known book: dropped, not retried forever")
        server.on("POST", "/v1/me/library/B1/bookmarks", FakeServer.error(400, "invalid_request", "bad"))
        repo.add("B1", 2, null)
        assertTrue(dao.unsyncedBookmarks("u1").isEmpty())
        server.on("POST", "/v1/me/library/B1/bookmarks", FakeServer.error(409, "limit_reached"))
        repo.add("B1", 3, null)
        server.on("POST", "/v1/me/library/B1/bookmarks", FakeServer.error(429, "rate_limited"))
        repo.add("B1", 4, null)
        assertEquals(listOf(3L, 4L), dao.unsyncedBookmarks("u1").map { it.positionMs })
    }
}
