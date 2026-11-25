package xyz.aresius.audiobooks.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import xyz.aresius.audiobooks.core.data.LibraryEntryEntity
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.testutil.MemoryLibraryDao
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.memorySessionStore
import xyz.aresius.audiobooks.testutil.testSession

class LastSessionRestorerTest {
    private val library = MemoryLibraryDao()
    private val dao = FakePlayerDao()
    private val hub = PlayerHub()
    private val restorer = LastSessionRestorer(memorySessionStore(testSession("http://x", "u1")), library, dao, hub)

    private fun book(asin: String) = AudiobooksJson.decodeFromString(Book.serializer(), Samples.book(asin, "Book $asin"))

    private suspend fun entry(asin: String, status: String = "listening", position: Long = 600_000, updated: Long = 1000, user: String = "u1") =
        library.upsert(LibraryEntryEntity(user, asin, AudiobooksJson.encodeToString(Book.serializer(), book(asin)), status, "hash-$asin", position, null, 0, 1.25, updated))

    @Test fun `the last listening book comes back paused with its stored position and no playback`() = runBlocking {
        entry("A1", updated = 1000)
        entry("A2", position = 1_800_000, updated = 2000)
        entry("A3", status = "saved", position = 0, updated = 3000)
        restorer.restore("u1")
        val s = hub.state.value
        assertEquals("A2", s.asin)
        assertEquals(PlayerPhase.Ready, s.phase)
        assertTrue(s.restored)
        assertFalse(s.playing)
        assertEquals(1_800_000L, s.positionMs)
        assertEquals(660 * 60_000L, s.durationMs)
        assertFalse(s.durationExact)
        assertEquals(1.25f, s.speed)
        assertEquals("hash-A2", s.releaseHash)
        assertEquals("Book A2", s.book?.title)
    }

    @Test fun `the players own newer progress row wins over the library copy`() = runBlocking {
        entry("A1", position = 600_000, updated = 1000)
        dao.putProgress(ProgressEntity("u1", "A1", "listening", "hash-new", 700_000, null, 0, 1.5, 1500, dirty = true))
        restorer.restore("u1")
        assertEquals(700_000L, hub.state.value.positionMs)
        assertEquals("hash-new", hub.state.value.releaseHash)
        assertEquals(1.5f, hub.state.value.speed)
    }

    @Test fun `a book finished on this phone since is not restored`() = runBlocking {
        entry("A1", updated = 1000)
        dao.putProgress(ProgressEntity("u1", "A1", "finished", null, 39_600_000, null, 0, 1.0, 1500, dirty = false))
        restorer.restore("u1")
        assertNull(hub.state.value.asin)
    }

    @Test fun `nothing is restored over a book that is already open, or for another user, or without progress`() = runBlocking {
        entry("A1")
        entry("A9", user = "u2", updated = 5000)
        hub.update { copy(phase = PlayerPhase.Loading, asin = "OPEN") }
        restorer.restore("u1")
        assertEquals("OPEN", hub.state.value.asin)
        assertFalse(hub.state.value.restored)
        hub.reset()
        entry("A5", position = 0, updated = 9000)
        restorer.restore("u3")
        assertNull(hub.state.value.asin)
    }

    @Test fun `a restored mini player follows a library refresh, a closed player stays closed`() = runBlocking {
        entry("A1", position = 600_000, updated = 1000)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        restorer.start(scope)
        assertEquals(600_000L, hub.state.value.positionMs)
        entry("A1", position = 900_000, updated = 2000)
        assertEquals(900_000L, hub.state.value.positionMs, "another phone listened on: the mini player follows")
        entry("A1", status = "finished", position = 900_000, updated = 3000)
        assertNull(hub.state.value.asin, "finished elsewhere: nothing to resume")
        entry("A2", position = 5_000, updated = 4000)
        assertEquals("A2", hub.state.value.asin)
        hub.update { PlayerState(phase = PlayerPhase.Ready, asin = "A2", playing = true) }
        entry("A2", position = 5_500, updated = 4500)
        hub.reset()
        entry("A2", position = 6_000, updated = 5000)
        assertNull(hub.state.value.asin, "the user closed the player: a library write does not bring it back")
        scope.cancel()
    }
}
