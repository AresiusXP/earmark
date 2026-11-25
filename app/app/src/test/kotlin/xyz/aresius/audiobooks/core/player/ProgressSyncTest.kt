package xyz.aresius.audiobooks.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ErrorCode
import xyz.aresius.audiobooks.core.network.LibraryEntry

class ProgressSyncTest {
    private val dao = FakePlayerDao()
    private val remote = FakeProgressRemote()
    private val scheduler = Counter()
    private var wall = 1_000_000L
    private var mono = 0L
    private val sync = ProgressSync(dao, remote, scheduler, clock = { wall }, monotonic = { mono })

    private fun entry(asin: String, updatedAt: Long, position: Long = 5000, status: String = "listening", speed: Double = 1.5) =
        LibraryEntry(testBook(asin), status, "remote-hash", position, "r.mp3", 100, speed, updatedAt)

    @Test
    fun `the first write goes out at once, later ones at most every 30 seconds`() = runTest {
        assertEquals(SyncOutcome.Synced, sync.record("u1", "B1", point(position = 1000), force = false))
        assertEquals(1, remote.puts.size)
        wall += 5_000; mono += 5_000
        assertEquals(SyncOutcome.Pending, sync.record("u1", "B1", point(position = 6000), force = false))
        wall += 20_000; mono += 20_000
        assertEquals(SyncOutcome.Pending, sync.record("u1", "B1", point(position = 26000), force = false))
        assertEquals(1, remote.puts.size)
        assertTrue(dao.progress("u1", "B1")!!.dirty, "local row is ahead of the server")
        assertEquals(26000, dao.progress("u1", "B1")!!.positionMs)
        wall += 6_000; mono += 6_000
        assertEquals(SyncOutcome.Synced, sync.record("u1", "B1", point(position = 32000), force = false))
        assertEquals(2, remote.puts.size)
        assertEquals(32000, remote.puts.last().second.positionMs)
        assertFalse(dao.progress("u1", "B1")!!.dirty)
    }

    @Test
    fun `a forced write (pause, stop) ignores the interval`() = runTest {
        sync.record("u1", "B1", point(), force = false)
        mono += 1_000
        assertEquals(SyncOutcome.Synced, sync.record("u1", "B1", point(position = 9000), force = true))
        assertEquals(2, remote.puts.size)
    }

    @Test
    fun `the write carries the contract fields and the client clock`() = runTest {
        sync.record("u1", "B1", point(status = "listening", hash = "abc", position = 70_000, path = "Part 2.mp3", filePosition = 10_000, speed = 1.25), force = true)
        val w = remote.puts.single().second
        assertEquals("listening", w.status)
        assertEquals("abc", w.releaseHash)
        assertEquals(70_000L, w.positionMs)
        assertEquals("Part 2.mp3", w.filePath)
        assertEquals(10_000L, w.filePositionMs)
        assertEquals(1.25, w.speed)
        assertEquals(wall, w.updatedAt)
    }

    @Test
    fun `offline the latest state per book is kept and retried later`() = runTest {
        remote.failure = ApiError.Network
        assertEquals(SyncOutcome.Pending, sync.record("u1", "B1", point(position = 1000), force = true))
        wall += 1000
        assertEquals(SyncOutcome.Pending, sync.record("u1", "B1", point(position = 2000), force = true))
        sync.record("u1", "B2", point(position = 3000), force = true)
        assertTrue(scheduler.scheduled >= 1)
        assertTrue(sync.pending())
        assertEquals(2, dao.dirtyProgress("u1").size)

        remote.failure = null
        assertTrue(sync.flush("u1"))
        assertFalse(sync.pending())
        assertEquals(listOf("B1" to 2000L, "B2" to 3000L), remote.puts.map { it.first to it.second.positionMs })
    }

    @Test
    fun `a flush that still fails reports pending`() = runTest {
        remote.failure = ApiError.Http(503, ErrorCode.Other)
        sync.record("u1", "B1", point(), force = true)
        assertFalse(sync.flush("u1"))
        assertTrue(sync.pending())
    }

    @Test
    fun `a write the server refuses for good is not retried forever`() = runTest {
        remote.failure = ApiError.Http(404, ErrorCode.NotFound)
        assertEquals(SyncOutcome.Rejected, sync.record("u1", "B1", point(), force = true))
        assertFalse(sync.pending())
        assertEquals(1000, dao.progress("u1", "B1")!!.positionMs, "the local copy stays")
    }

    @Test
    fun `last write wins - the server keeps a newer write of another device and the phone adopts it`() = runTest {
        remote.entries["B1"] = entry("B1", updatedAt = wall + 10_000, position = 99_000)
        assertEquals(SyncOutcome.Synced, sync.record("u1", "B1", point(position = 1000), force = true))
        val row = dao.progress("u1", "B1")!!
        assertEquals(99_000, row.positionMs)
        assertFalse(row.dirty)
    }

    @Test
    fun `opening a book takes the server entry when it is newer`() = runTest {
        sync.record("u1", "B1", point(position = 1000), force = true)
        remote.entries["B1"] = entry("B1", updatedAt = wall + 5_000, position = 77_000, speed = 1.8)
        val resume = sync.resolve("u1", "B1")!!
        assertEquals(77_000, resume.positionMs)
        assertEquals(1.8, resume.speed)
        assertEquals("r.mp3", resume.filePath)
        assertEquals(77_000, dao.progress("u1", "B1")!!.positionMs)
        assertFalse(dao.progress("u1", "B1")!!.dirty)
    }

    @Test
    fun `opening a book keeps the local entry when it is newer or equal and pushes it if unconfirmed`() = runTest {
        remote.failure = ApiError.Network
        sync.record("u1", "B1", point(position = 5000), force = true)
        remote.failure = null
        remote.entries["B1"] = entry("B1", updatedAt = wall - 1, position = 1)
        val resume = sync.resolve("u1", "B1")!!
        assertEquals(5000, resume.positionMs)
        assertEquals(1, remote.puts.size)
        assertEquals(5000, remote.puts.single().second.positionMs)

        remote.entries["B1"] = entry("B1", updatedAt = wall, position = 123)
        assertEquals(5000, sync.resolve("u1", "B1")!!.positionMs, "a tie keeps the local one")
    }

    @Test
    fun `opening a book offline uses the local row, and nothing at all when nobody knows the book`() = runTest {
        remote.failure = ApiError.Network
        assertNull(sync.resolve("u1", "B9"))
        sync.record("u1", "B1", point(position = 4000), force = true)
        assertEquals(4000, sync.resolve("u1", "B1")!!.positionMs)
    }

    @Test
    fun `a slow server does not hold the start of playback`() = runTest {
        sync.record("u1", "B1", point(position = 4000), force = true)
        remote.getDelayMs = 60_000
        assertEquals(4000, sync.resolve("u1", "B1")!!.positionMs)
    }

    @Test
    fun `a book the server knows but the phone does not resumes from the server`() = runTest {
        remote.entries["B2"] = entry("B2", updatedAt = 500, position = 8000)
        assertEquals(8000, sync.resolve("u1", "B2")!!.positionMs)
        assertEquals(8000, dao.progress("u1", "B2")!!.positionMs)
    }

    @Test
    fun `progress is scoped by user`() = runTest {
        sync.record("u1", "B1", point(position = 1000), force = true)
        remote.failure = ApiError.Network
        assertNull(sync.resolve("u2", "B1"))
        assertTrue(dao.dirtyProgress("u2").isEmpty())
        sync.record("u2", "B1", point(position = 9000), force = true)
        assertEquals(1000, dao.progress("u1", "B1")!!.positionMs)
        assertEquals(9000, dao.progress("u2", "B1")!!.positionMs)
    }

    @Test
    fun `speed is part of the saved state per book`() = runTest {
        sync.record("u1", "B1", point(speed = 1.75), force = true)
        sync.record("u1", "B2", point(speed = 0.8), force = true)
        remote.failure = ApiError.Network
        assertEquals(1.75, sync.resolve("u1", "B1")!!.speed)
        assertEquals(0.8, sync.resolve("u1", "B2")!!.speed)
    }
}
