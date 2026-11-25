package xyz.aresius.audiobooks.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.memorySessionStore
import xyz.aresius.audiobooks.testutil.testSession

class PlayerStarterTest {
    private val dao = FakePlayerDao()
    private val remote = FakeProgressRemote()
    private val hub = PlayerHub()

    private fun starter(sessions: xyz.aresius.audiobooks.core.auth.SessionStore, server: FakeServer) =
        PlayerStarter(sessions, dao, ProgressSync(dao, remote, Counter()), BookmarkRepository(dao, { server.api }, sessions) { 1L }, DurationReporter(dao, { server.api }, Counter()), hub)

    @Test fun `wiping removes progress, remembered releases and bookmarks of the phone and resets the hub`() = runBlocking {
        FakeServer().use { server ->
            val s = starter(memorySessionStore(testSession(server.baseUrl)), server)
            remote.failure = ApiError.Network
            ProgressSync(dao, remote, Counter()).record("u1", "B1", point(), force = true)
            dao.putPref(BookPrefEntity("u1", "B1", "h", "torbox"))
            dao.putBookmark(BookmarkEntity(userId = "u1", asin = "B1", serverId = null, positionMs = 1, note = null, createdAtMs = 1))
            hub.update { copy(asin = "B1") }
            s.wipe()
            assertTrue(dao.progress.isEmpty() && dao.prefs.isEmpty())
            assertEquals(0, dao.bookmarks("u1", "B1").size)
            assertEquals(null, hub.state.value.asin)
        }
    }

    @Test fun `a signed-in start sends what was left pending and keeps the data`() = runBlocking {
        FakeServer().use { server ->
            server.onJson("GET", "/v1/me/library/B2/bookmarks", """{"bookmarks":[]}""")
            remote.failure = ApiError.Network
            val sync = ProgressSync(dao, remote, Counter())
            sync.record("u1", "B1", point(position = 4242), force = true)
            remote.failure = null
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            starter(memorySessionStore(testSession(server.baseUrl, "u1")), server).start(scope)
            val deadline = System.currentTimeMillis() + 5_000
            while (remote.puts.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            scope.cancel()
            assertEquals(4242L, remote.puts.single().second.positionMs)
            assertEquals(4242L, dao.progress("u1", "B1")!!.positionMs)
        }
    }

    @Test fun `a signed-out start wipes what a previous account left`() = runBlocking {
        FakeServer().use { server ->
            ProgressSync(dao, remote, Counter()).record("u1", "B1", point(), force = true)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            starter(memorySessionStore(), server).start(scope)
            val deadline = System.currentTimeMillis() + 5_000
            while (dao.progress.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            scope.cancel()
            assertTrue(dao.progress.isEmpty())
        }
    }

    @Test fun `a signed-in start also reports the durations learned before the app was closed`() = runBlocking {
        FakeServer().use { server ->
            server.onJson("POST", "/v1/releases/h1/durations", """{"durations":[{"path":"B/1.mp3","duration_ms":61000}],"ignored":0}""")
            dao.putDuration(FileDurationEntity("h1", "B/1.mp3", 61_000))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            starter(memorySessionStore(testSession(server.baseUrl, "u1")), server).start(scope)
            val deadline = System.currentTimeMillis() + 5_000
            while (dao.unreportedDurations().isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            scope.cancel()
            assertTrue(dao.unreportedDurations().isEmpty())
            assertEquals(1, server.requestsTo("POST", "/v1/releases/h1/durations").size)
        }
    }
}
