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
    private val repo = BookmarkRepository(dao, { server.api }, sessions) { now }

    private fun bookmarkJson(id: String, pos: Long, note: String? = null) =
        """{"id":"$id","position_ms":$pos,${note?.let { """"note":"$it",""" } ?: ""}"created_at":"2026-11-25T10:00:00Z"}"""

    @Test
    fun `add stores locally and mirrors to the server`() = runBlocking {
        server.onJson("POST", "/v1/me/library/B1/bookmarks", bookmarkJson("srv-1", 5000, "nice"))
        val b = repo.add("B1", 5000, "  nice ")!!
        assertEquals("nice", b.note)
        assertTrue(b.synced)
        assertEquals("""{"position_ms":5000,"note":"nice"}""", server.lastBody("POST", "/v1/me/library/B1/bookmarks"))
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
        server.on("POST", "/v1/me/library/B1/bookmarks", FakeServer.error(404, "not_found"))
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
}
