package xyz.aresius.audiobooks.core.player

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerPlayingBookTest {
    private val hub = PlayerHub()
    private val playing = PlayerPlayingBook(hub)

    @Test fun reportsTheBookThePlayerHoldsAndNothingWhenIdle() {
        assertNull(playing.currentAsin())
        hub.update { PlayerState(phase = PlayerPhase.Ready, asin = "B0TEST") }
        assertEquals("B0TEST", playing.currentAsin())
        hub.update { PlayerState(phase = PlayerPhase.Idle, asin = "B0TEST") }
        assertNull(playing.currentAsin())
    }

    @Test fun theBookThePlayerHoldsCannotBeDeleted() {
        hub.update { PlayerState(phase = PlayerPhase.Loading, asin = "A") }
        assertFalse(playing.beginDelete("A"), "opening counts as playing")
        assertTrue(playing.beginDelete("B"))
        playing.endDelete("B")
        hub.reset()
        assertTrue(playing.beginDelete("A"))
        playing.endDelete("A")
    }

    @Test fun aBookBeingDeletedMakesThePlayerWaitBeforeItLooksAtTheFiles() {
        assertTrue(playing.beginDelete("A"))
        hub.update { PlayerState(phase = PlayerPhase.Loading, asin = "A") } // the user taps play while the deletion runs
        val wait = assertNotNull(hub.deletionOf("A"))
        assertFalse(wait.isCompleted)
        assertNull(hub.deletionOf("B"))
        playing.endDelete("A")
        assertTrue(wait.isCompleted)
        assertNull(hub.deletionOf("A"))
    }

    @Test fun aDeletionAndAPlayStartingTogetherNeverLeaveAPlayingBookWithDeletedFiles() {
        repeat(300) { round ->
            val h = PlayerHub()
            val p = PlayerPlayingBook(h)
            val go = CountDownLatch(1)
            val deleted = CopyOnWriteArrayList<String>()
            val deleter = thread {
                go.await()
                if (p.beginDelete("A")) {
                    // the files are removed here; the player must not have claimed the book before beginDelete said yes
                    Thread.yield()
                    deleted += "A"
                    p.endDelete("A")
                }
            }
            val player = thread {
                go.await()
                h.update { PlayerState(phase = PlayerPhase.Loading, asin = "A") }
                // after the claim a deletion that said yes may still be running: the player waits for it
                h.deletionOf("A")
            }
            go.countDown()
            deleter.join(5000); player.join(5000)
            val wait = h.deletionOf("A")
            assertTrue(wait == null || wait.isCompleted, "round $round: the deletion ended")
            if ("A" in deleted) {
                // deleted: the player, if it started, saw the deletion (waits) -> it will find no local files and stream; never half and half
                assertNull(h.deletionOf("A"))
            }
        }
    }

    @Test fun aClaimThatHappenedFirstAlwaysWins() {
        repeat(200) {
            val h = PlayerHub()
            val p = PlayerPlayingBook(h)
            h.update { PlayerState(phase = PlayerPhase.Ready, asin = "A") }
            val answers = CopyOnWriteArrayList<Boolean>()
            val threads = (1..4).map { thread { answers += p.beginDelete("A") } }
            threads.forEach { it.join(5000) }
            assertEquals(listOf(false, false, false, false), answers.toList())
        }
    }
}
