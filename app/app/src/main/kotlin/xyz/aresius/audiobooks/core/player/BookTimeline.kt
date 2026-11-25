package xyz.aresius.audiobooks.core.player

/** One audio file of a book, in listening order. [index] is the provider's file index (null for a file that only exists locally). */
data class BookFile(
    val path: String,
    val index: Int?,
    val sizeBytes: Long,
    /** Null until the player has prepared the file once (then it is persisted, see [PlayerDao]). */
    val durationMs: Long? = null,
)

/** A place inside the files: [fileIndex] is the position in [BookTimeline.files] (not the provider index). */
data class FilePosition(val fileIndex: Int, val offsetMs: Long)

/**
 * One timeline for the whole book over its ordered files. Durations that are not known yet are estimated: what is left of the catalog
 * runtime (after the known files) is shared among the unknown files by size; without a runtime, by an assumed bitrate. Learning a
 * duration ([withDuration]) gives a new, better timeline; the mapping position <-> (file, offset) is exact for the known files.
 */
class BookTimeline(val files: List<BookFile>, val runtimeMs: Long) {
    private val durations: LongArray
    private val starts: LongArray

    /** Length of the whole book (the sum of the file durations, estimated where unknown). */
    val totalMs: Long

    /** False while at least one file duration is an estimate. */
    val exact: Boolean

    init {
        val known = files.sumOf { it.durationMs ?: 0L }
        val unknown = files.filter { it.durationMs == null }
        val left = (runtimeMs - known).coerceAtLeast(0)
        val unknownBytes = unknown.sumOf { it.sizeBytes.coerceAtLeast(0) }
        durations = LongArray(files.size) { i ->
            val f = files[i]
            f.durationMs ?: when {
                left > 0 && unknownBytes > 0 -> (left.toDouble() * f.sizeBytes.coerceAtLeast(0) / unknownBytes).toLong()
                left > 0 -> left / unknown.size
                else -> f.sizeBytes.coerceAtLeast(0) * 8_000L / ASSUMED_BITRATE_BPS
            }.coerceAtLeast(1)
        }
        starts = LongArray(files.size + 1)
        for (i in files.indices) starts[i + 1] = starts[i] + durations[i]
        totalMs = starts[files.size]
        exact = unknown.isEmpty()
    }

    val isEmpty: Boolean get() = files.isEmpty()

    fun durationOf(fileIndex: Int): Long = durations[fileIndex]

    fun startOf(fileIndex: Int): Long = starts[fileIndex]

    /** The book position of [offsetMs] inside file [fileIndex] (offset clamped to the file). */
    fun toBook(fileIndex: Int, offsetMs: Long): Long {
        if (files.isEmpty()) return 0
        val i = fileIndex.coerceIn(0, files.lastIndex)
        return starts[i] + offsetMs.coerceIn(0, durations[i])
    }

    /** The file and offset of a book position (clamped to 0..[totalMs]); the end of the book is the end of the last file. */
    fun toFile(bookMs: Long): FilePosition {
        if (files.isEmpty()) return FilePosition(0, 0)
        val p = bookMs.coerceIn(0, totalMs)
        var lo = 0
        var hi = files.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= p) lo = mid else hi = mid - 1
        }
        return FilePosition(lo, (p - starts[lo]).coerceAtMost(durations[lo]))
    }

    fun indexOfPath(path: String): Int = files.indexOfFirst { it.path == path }

    /** This timeline with the real duration of a file; the same instance when nothing changes. */
    fun withDuration(fileIndex: Int, durationMs: Long): BookTimeline {
        if (fileIndex !in files.indices || durationMs <= 0 || files[fileIndex].durationMs == durationMs) return this
        return BookTimeline(files.mapIndexed { i, f -> if (i == fileIndex) f.copy(durationMs = durationMs) else f }, runtimeMs)
    }

    companion object {
        /** Only used without a catalog runtime: 64 kbit/s is a typical audiobook bitrate. */
        const val ASSUMED_BITRATE_BPS = 64_000L
    }
}
