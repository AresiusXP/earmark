package xyz.aresius.audiobooks.core.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.aresius.audiobooks.testutil.DownloadKit
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.testSession

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = FakeServer()
    private val app = TestApp(server)
    private val kit = DownloadKit(context, app, prefs = DownloadPrefs(storageLimitBytes = 3_000))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val day = StorageManager.DAY_MS

    @AfterTest fun tearDown() {
        scope.cancel()
        kit.close()
        server.close()
    }

    private fun exists(asin: String) = runBlocking { kit.dao.book("u1", asin) != null }

    @Test fun lruEvictsTheOldestFinishedBookFirst() = runBlocking<Unit> {
        listOf("A", "B", "C").forEach { kit.seed(it, listOf(1000)) }
        kit.finished.map.putAll(mapOf("A" to 100L, "B" to 200L, "C" to 300L))
        kit.storage.ensureRoom("u1", "NEW", 1000)
        assertFalse(exists("A"), "the book finished longest ago goes first")
        assertTrue(exists("B") && exists("C"))
        assertFalse(File(kit.root, "A").exists(), "its files are gone too")
        kit.storage.ensureRoom("u1", "NEW", 3000)
        assertFalse(exists("B"))
        assertFalse(exists("C"))
    }

    @Test fun theBookThatIsPlayingIsNeverEvicted() = runBlocking<Unit> {
        listOf("A", "B", "C").forEach { kit.seed(it, listOf(1000)) }
        kit.finished.map.putAll(mapOf("A" to 100L, "B" to 200L, "C" to 300L))
        kit.playing.asin = "A"
        kit.storage.ensureRoom("u1", "NEW", 1000)
        assertTrue(exists("A"), "A is the oldest finished book but it is playing")
        assertFalse(exists("B"))
        // Everything left is playing or unfinished: nothing more can be evicted.
        kit.finished.map.remove("C")
        val e = assertFailsWith<DownloadException> { kit.storage.ensureRoom("u1", "NEW", 2000) }
        assertEquals(FailReason.StorageLimit, e.reason)
        assertTrue(exists("A") && exists("C"))
    }

    /** The player starts the oldest book AFTER the victim was chosen (a snapshot said "nothing is playing"): it must survive. */
    @Test fun aBookThatStartsPlayingAfterTheVictimWasChosenSurvives() = runBlocking<Unit> {
        listOf("A", "B", "C").forEach { kit.seed(it, listOf(1000)) }
        kit.finished.map.putAll(mapOf("A" to 100L, "B" to 200L, "C" to 300L))
        var snapshots = 0
        val racing = object : PlayingBook {
            override fun currentAsin(): String? = null.also { snapshots++ } // what the victim selection sees
            override fun beginDelete(asin: String): Boolean = asin != "A" // by deletion time the player holds A
        }
        val storage = StorageManager(kit.dao, kit.root, kit.settings, kit.finished, racing, FreeSpace { Long.MAX_VALUE }, { 0L }, minFreeBytes = 0, orphanGraceMs = 0)
        storage.ensureRoom("u1", "NEW", 1000)
        assertTrue(snapshots > 0)
        assertTrue(exists("A"), "A started playing after it was chosen")
        assertTrue(File(kit.root, "A").exists(), "its files are still there")
        assertFalse(exists("B"), "the next oldest is evicted instead")
        assertTrue(exists("C"))
    }

    @Test fun everyDeletionIsBracketedByBeginAndEndAndEndsEvenWhenSkipped() = runBlocking<Unit> {
        listOf("A", "B", "C").forEach { kit.seed(it, listOf(1000)) }
        kit.finished.map.putAll(mapOf("A" to 100L, "B" to 200L, "C" to 300L))
        val log = ArrayList<String>()
        val p = object : PlayingBook {
            override fun currentAsin(): String? = null
            override fun beginDelete(asin: String): Boolean { log += "begin $asin"; return asin != "A" }
            override fun endDelete(asin: String) { log += "end $asin" }
        }
        val storage = StorageManager(kit.dao, kit.root, kit.settings, kit.finished, p, FreeSpace { Long.MAX_VALUE }, { 0L }, minFreeBytes = 0, orphanGraceMs = 0)
        storage.ensureRoom("u1", "NEW", 1000)
        assertEquals(listOf("begin A", "begin B", "end B"), log, "a refused begin needs no end; an accepted one always has it")
    }

    /** A book that was finished when it was chosen, but its job runs again by deletion time (re-downloaded / repaired): not deleted. */
    @Test fun aBookWhoseJobIsActiveAgainByDeletionTimeIsNotDeleted() = runBlocking<Unit> {
        listOf("A", "B", "C").forEach { kit.seed(it, listOf(1000)) }
        kit.finished.map.putAll(mapOf("A" to 100L, "B" to 200L, "C" to 300L))
        val p = object : PlayingBook {
            override fun currentAsin(): String? = null
            override fun beginDelete(asin: String): Boolean {
                if (asin == "A") runBlocking { kit.dao.updateBook("u1", "A") { it.copy(state = JobState.QUEUED) } }
                return true
            }
        }
        val storage = StorageManager(kit.dao, kit.root, kit.settings, kit.finished, p, FreeSpace { Long.MAX_VALUE }, { 0L }, minFreeBytes = 0, orphanGraceMs = 0)
        storage.ensureRoom("u1", "NEW", 1000)
        assertTrue(exists("A") && File(kit.root, "A").exists(), "an active job is never evicted")
        assertEquals(JobState.QUEUED, kit.dao.book("u1", "A")!!.state)
        assertFalse(exists("B"))
    }

    @Test fun autoDeleteAlsoAsksThePlayerAtDeletionTime() = runBlocking<Unit> {
        kit.seed("A", listOf(1000))
        kit.finished.map["A"] = 0L
        val p = object : PlayingBook {
            override fun currentAsin(): String? = null
            override fun beginDelete(asin: String) = false
        }
        val storage = StorageManager(kit.dao, kit.root, kit.settings, kit.finished, p, FreeSpace { Long.MAX_VALUE }, { 10L * 24 * 3_600_000 }, minFreeBytes = 0, orphanGraceMs = 0)
        storage.autoDeleteFinished("u1", 1)
        assertTrue(exists("A"))
    }

    @Test fun onlyFinishedBooksAreEvictedNeverOnesStillBeingListenedToOrDownloaded() = runBlocking<Unit> {
        kit.seed("LISTENING", listOf(1000))
        kit.seed("DOWNLOADING", listOf(1000), state = JobState.DOWNLOADING, complete = false)
        kit.seed("DONE", listOf(1000))
        kit.finished.map["DONE"] = 1L
        kit.storage.ensureRoom("u1", "NEW", 2000)
        assertTrue(exists("LISTENING") && exists("DOWNLOADING"))
        assertFalse(exists("DONE"))
        assertEquals(FailReason.StorageLimit, assertFailsWith<DownloadException> { kit.storage.ensureRoom("u1", "NEW", 5000) }.reason)
    }

    @Test fun loweringTheLimitEvictsAfterwards() = runBlocking<Unit> {
        listOf("A", "B", "C").forEach { kit.seed(it, listOf(1000)) }
        kit.finished.map.putAll(mapOf("A" to 1L, "B" to 2L, "C" to 3L))
        kit.settings.setStorageLimit(1500)
        kit.storage.enforceLimit("u1")
        assertEquals(1000L, kit.storage.usedBytes("u1"))
        assertTrue(exists("C"))
    }

    @Test fun anUnlimitedStorageNeverEvicts() = runBlocking<Unit> {
        kit.settings.setStorageLimit(0)
        kit.seed("A", listOf(1000))
        kit.finished.map["A"] = 1L
        kit.storage.ensureRoom("u1", "NEW", 10_000_000)
        assertTrue(exists("A"))
    }

    @Test fun notEnoughFreeSpaceRefusesBeforeStarting() = runBlocking<Unit> {
        kit.free = 500
        assertEquals(FailReason.NoSpace, assertFailsWith<DownloadException> { kit.storage.ensureRoom("u1", "NEW", 1000) }.reason)
        kit.free = 1000
        kit.storage.ensureRoom("u1", "NEW", 1000)
    }

    @Test fun finishedBooksAreDeletedAfterTheConfiguredNumberOfDays() = runBlocking<Unit> {
        kit.seed("OLD", listOf(100)); kit.seed("RECENT", listOf(100)); kit.seed("UNFINISHED", listOf(100)); kit.seed("PLAYING", listOf(100))
        kit.finished.map.putAll(mapOf("OLD" to kit.now - 10 * day, "RECENT" to kit.now - 2 * day, "PLAYING" to kit.now - 20 * day))
        kit.playing.asin = "PLAYING"
        kit.storage.autoDeleteFinished("u1", 0)
        assertTrue(exists("OLD"), "off by default (0 days)")
        kit.storage.autoDeleteFinished("u1", 7)
        assertFalse(exists("OLD"))
        assertTrue(exists("RECENT") && exists("UNFINISHED") && exists("PLAYING"))
    }

    @Test fun orphanFilesAndDirectoriesAreCleanedUpButKnownFilesStay() = runBlocking<Unit> {
        val files = kit.seed("A", listOf(100, 100))
        val dir = files[0].parentFile!!
        val orphanFile = File(dir, "stray.mp3").apply { writeBytes(ByteArray(10)) }
        val orphanPart = File(dir, "gone.mp3.part").apply { writeBytes(ByteArray(10)) }
        val ownedPart = File(dir, "part1.mp3.part").apply { writeBytes(ByteArray(10)) }
        val orphanDir = File(File(kit.root, "REMOVED"), "c".repeat(40)).apply { mkdirs() }
        File(orphanDir, "x.mp3").writeBytes(ByteArray(5))
        File(kit.root, "loose.bin").writeBytes(ByteArray(5))
        kit.now = System.currentTimeMillis() + 5_000 // the files above are "old enough" (grace 0)
        kit.storage.cleanupOrphans()
        assertTrue(files.all { it.exists() })
        assertTrue(ownedPart.exists(), "the .part of a file that is still wanted stays")
        assertFalse(orphanFile.exists())
        assertFalse(orphanPart.exists())
        assertFalse(File(kit.root, "REMOVED").exists())
        assertFalse(File(kit.root, "loose.bin").exists())
    }

    @Test fun freshOrphansAreSparedBecauseAJustStartedDownloadMayOwnThem() = runBlocking<Unit> {
        val grace = StorageManager(kit.dao, kit.root, kit.settings, kit.finished, kit.playing, FreeSpace { Long.MAX_VALUE }, { System.currentTimeMillis() }, orphanGraceMs = 10 * 60_000L)
        val fresh = File(File(kit.root, "NEWBOOK"), "d".repeat(40)).apply { mkdirs() }
        File(fresh, "a.mp3.part").writeBytes(ByteArray(5))
        grace.cleanupOrphans()
        assertTrue(fresh.exists())
    }

    @Test fun torrentBookDirectoriesAreLeftToTheEngine() = runBlocking<Unit> {
        val files = kit.seed("T", listOf(100), source = "torrent")
        val engineFile = File(files[0].parentFile, ".resume-data").apply { writeBytes(ByteArray(3)) }
        kit.now = System.currentTimeMillis() + 5_000
        kit.storage.cleanupOrphans()
        assertTrue(engineFile.exists())
    }

    @Test fun rowsOfOneUserAreInvisibleToAnother() = runBlocking<Unit> {
        kit.seed("A", listOf(1000), userId = "u1")
        kit.seed("B", listOf(500), userId = "u2", hash = "e".repeat(40))
        assertEquals(1000L, kit.storage.usedBytes("u1"))
        assertEquals(500L, kit.storage.usedBytes("u2"))
        assertEquals(listOf("A"), kit.dao.books("u1").map { it.asin })
        assertNull(kit.dao.book("u1", "B"))
        assertEquals(listOf("B"), kit.dao.observeBooks("u2").first().map { it.asin })
        assertTrue(kit.dao.observeCompleteFiles("u1").first().none { it.asin == "B" })
        kit.storage.deleteAll("u1")
        assertEquals(1, kit.dao.books("u2").size, "deleting one user's downloads leaves the other's alone")
    }

    @Test fun wipeEverythingRemovesRowsAndFiles() = runBlocking<Unit> {
        kit.seed("A", listOf(100)); kit.seed("B", listOf(100), userId = "u2", hash = "e".repeat(40))
        kit.repository.wipe()
        assertTrue(kit.dao.allBooks().isEmpty() && kit.dao.allFiles().isEmpty())
        assertFalse(kit.root.exists())
        assertEquals(1, kit.scheduler.cancelAllCount)
    }

    @Test fun aCompleteRowWhoseFileVanishedBecomesPendingAndTheBookIsQueuedAgain() = runBlocking<Unit> {
        val files = kit.seed("A", listOf(100, 100))
        files[1].delete()
        assertEquals(setOf("A"), kit.storage.repairMissingFiles("u1"))
        assertEquals(listOf(FileState.COMPLETE, FileState.PENDING), kit.dao.files("u1", "A").map { it.state })
        assertEquals(JobState.QUEUED, kit.dao.book("u1", "A")!!.state)
    }

    // --- LocalFiles ------------------------------------------------------------------------------------------------------

    private fun localFiles() = DownloadedLocalFiles(kit.dao, kit.sessions, kit.storage, scope)

    @Test fun localFilesReturnsOnlyCompleteFilesOfTheSignedInUser() = runBlocking<Unit> {
        val hash = "b".repeat(40)
        val done = kit.seed("A", listOf(100), hash = hash)[0]
        val local = localFiles()
        assertEquals(done.canonicalPath, local.localFile("A", hash, "part0.mp3")!!.canonicalPath)
        assertNull(local.localFile("A", hash, "other.mp3"))
        assertNull(local.localFile("A", "f".repeat(40), "part0.mp3"), "another release is another file")
        assertTrue(local.hasDownloads("A").first())
        assertFalse(local.hasDownloads("Z").first())

        // A partial file (pending row, `.part` on disk) is never returned.
        kit.seed("P", listOf(100), complete = false, state = JobState.DOWNLOADING)
        File(File(File(kit.root, "P"), hash), "part0.mp3.part").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(40)) }
        File(File(File(kit.root, "P"), hash), "part0.mp3").writeBytes(ByteArray(40)) // even a same-named file of a torrent engine
        assertNull(local.localFile("P", hash, "part0.mp3"))
        assertFalse(local.hasDownloads("P").first())
    }

    @Test fun localFilesChecksTheFileOnDiskAndTheOwner() = runBlocking<Unit> {
        val hash = "b".repeat(40)
        val f = kit.seed("A", listOf(100), hash = hash)[0]
        kit.seed("B", listOf(100), userId = "u2", hash = hash)
        val local = localFiles()
        assertNotNull(local.localFile("A", hash, "part0.mp3"))
        assertNull(local.localFile("B", hash, "part0.mp3"), "another user's download is not ours")
        f.writeBytes(ByteArray(10))
        assertNull(local.localFile("A", hash, "part0.mp3"), "a file whose size changed is not trusted")
        f.delete()
        assertNull(local.localFile("A", hash, "part0.mp3"))
        // Switching user shows the other user's files and hides ours.
        app.sessions.save(testSession(server.baseUrl, userId = "u2"))
        assertTrue(local.hasDownloads("B").first { it })
    }

    @Test fun localFilesMatchesAServerPathWithALeadingSlash() = runBlocking<Unit> {
        val hash = "b".repeat(40)
        kit.seed("A", listOf(100), hash = hash)
        assertNotNull(localFiles().localFile("A", hash, "/part0.mp3"))
    }
}
