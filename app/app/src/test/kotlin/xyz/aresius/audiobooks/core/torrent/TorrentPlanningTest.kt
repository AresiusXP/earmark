package xyz.aresius.audiobooks.core.torrent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TorrentPlanningTest {
    @Test fun `selected files get descending priorities in request order, others are never downloaded`() {
        // 6 files; the request asks for files 4, 0 and 2 in that order
        assertContentEquals(intArrayOf(6, 0, 5, 0, 7, 0), FilePlanner.priorities(6, listOf(4, 0, 2), emptySet()))
    }

    @Test fun `priority never drops below 1 and unselected stay 0`() {
        val order = (0 until 12).toList()
        val p = FilePlanner.priorities(14, order, emptySet())
        assertContentEquals(intArrayOf(7, 6, 5, 4, 3, 2, 1, 1, 1, 1, 1, 1, 0, 0), p)
    }

    @Test fun `complete files drop out of the ranking`() {
        val p = FilePlanner.priorities(4, listOf(0, 1, 2), setOf(0))
        assertContentEquals(intArrayOf(1, 7, 6, 0), p)
    }

    @Test fun `out of range selections are ignored`() {
        assertContentEquals(intArrayOf(7, 0), FilePlanner.priorities(2, listOf(0, 5, -1), emptySet()))
    }

    @Test fun `deadline pieces are the first missing ones of the file`() {
        val have = setOf(10, 11, 13)
        assertEquals(listOf(12, 14, 15), FilePlanner.deadlinePieces(10, 15, { it in have }, window = 3))
        assertEquals(listOf(14, 15), FilePlanner.deadlinePieces(10, 15, { it < 14 }))
        assertEquals(emptyList(), FilePlanner.deadlinePieces(10, 9, { false }))
    }

    @Test fun `no peers for 3 minutes stalls, a peer resets it`() {
        val s = StallDetector()
        var t = 0L
        assertNull(s.update(t, 0, 0))
        t += 179_000; assertNull(s.update(t, 0, 0))
        t += 1_000; assertEquals(StallReason.NO_PEERS, s.update(t, 0, 0))
        t += 1_000; assertNull(s.update(t, 1, 0))
    }

    @Test fun `peers without progress for 5 minutes is NO_PROGRESS`() {
        val s = StallDetector()
        var t = 0L
        s.update(t, 3, 100)
        t += 299_000; assertNull(s.update(t, 3, 100))
        t += 1_000; assertEquals(StallReason.NO_PROGRESS, s.update(t, 3, 100))
        t += 1_000; assertNull(s.update(t, 3, 101))
    }

    @Test fun `reset forgets the time spent paused`() {
        val s = StallDetector()
        s.update(0, 0, 0)
        s.update(170_000, 0, 0)
        s.reset()
        assertNull(s.update(400_000, 0, 0))
        assertNull(s.update(500_000, 0, 0))
        assertEquals(StallReason.NO_PEERS, s.update(580_000, 0, 0))
    }
}
