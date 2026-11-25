package xyz.aresius.audiobooks.core.torrent

/**
 * File priorities (libtorrent: 0 = never download, 7 = first): the selected files in the order of the request, the first
 * incomplete one at 7, the next at 6, ... never below 1. Unselected files are 0 and are never created.
 * Complete files keep priority 1 so their pieces are not treated as unwanted.
 */
object FilePlanner {
    const val TOP = 7

    fun priorities(numFiles: Int, selectedInOrder: List<Int>, complete: Set<Int>): IntArray {
        val out = IntArray(numFiles)
        var rank = 0
        for (index in selectedInOrder) {
            if (index !in 0 until numFiles) continue
            out[index] = if (index in complete) 1 else maxOf(1, TOP - rank++)
        }
        return out
    }

    /** The pieces of the file's range that still need a deadline so that file becomes playable early: the first [window] missing ones. */
    fun deadlinePieces(firstPiece: Int, lastPiece: Int, have: (Int) -> Boolean, window: Int = 16): List<Int> {
        val out = ArrayList<Int>(window)
        var piece = firstPiece
        while (piece <= lastPiece && out.size < window) {
            if (!have(piece)) out += piece
            piece++
        }
        return out
    }
}

/** "No peers for 3 minutes" and "no progress for 5 minutes" (any time spent paused does not count). Pure: time is passed in. */
class StallDetector(
    private val noPeersMs: Long = 3 * 60_000L,
    private val noProgressMs: Long = 5 * 60_000L,
) {
    private var lastPeerAt = Long.MIN_VALUE
    private var lastProgressAt = Long.MIN_VALUE
    private var lastBytes = -1L

    fun reset() {
        lastPeerAt = Long.MIN_VALUE
        lastProgressAt = Long.MIN_VALUE
        lastBytes = -1L
    }

    fun update(nowMs: Long, peers: Int, bytesDone: Long): StallReason? {
        if (lastPeerAt == Long.MIN_VALUE) {
            lastPeerAt = nowMs
            lastProgressAt = nowMs
            lastBytes = bytesDone
        }
        if (peers > 0) lastPeerAt = nowMs
        if (bytesDone > lastBytes) lastProgressAt = nowMs
        lastBytes = bytesDone
        return when {
            nowMs - lastPeerAt >= noPeersMs -> StallReason.NO_PEERS
            nowMs - lastProgressAt >= noProgressMs -> StallReason.NO_PROGRESS
            else -> null
        }
    }
}
