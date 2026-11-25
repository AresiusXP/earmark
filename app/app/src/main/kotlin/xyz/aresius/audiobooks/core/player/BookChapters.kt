package xyz.aresius.audiobooks.core.player

import xyz.aresius.audiobooks.core.network.Chapter
import xyz.aresius.audiobooks.core.network.Chapters

data class BookChapter(val index: Int, val title: String, val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

enum class ChapterSource { Audible, Files }

/** The chapters of a book on its [BookTimeline], and the navigation over them. [source] says where they came from. */
class ChapterMap(val chapters: List<BookChapter>, val source: ChapterSource) {
    /** Index of the chapter that contains [bookMs] (the last one at or past the end); -1 without chapters. */
    fun indexAt(bookMs: Long): Int {
        if (chapters.isEmpty()) return -1
        var lo = 0
        var hi = chapters.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (chapters[mid].startMs <= bookMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun at(bookMs: Long): BookChapter? = chapters.getOrNull(indexAt(bookMs))

    /** Start of the chapter after the current one, or null in the last chapter. */
    fun nextStart(bookMs: Long): Long? = chapters.getOrNull(indexAt(bookMs) + 1)?.startMs

    /** Like a CD player: past [restartAfterMs] into a chapter, "previous" restarts it; else it goes to the chapter before (or the start). */
    fun previousTarget(bookMs: Long, restartAfterMs: Long = RESTART_AFTER_MS): Long {
        val i = indexAt(bookMs)
        if (i < 0) return 0
        val current = chapters[i]
        return when {
            bookMs - current.startMs > restartAfterMs -> current.startMs
            i > 0 -> chapters[i - 1].startMs
            else -> 0
        }
    }

    companion object {
        const val RESTART_AFTER_MS = 3_000L

        /** More than this fraction between the Audible runtime and the files' total and the Audible marks are not trusted. */
        const val MAX_RUNTIME_MISMATCH = 0.02

        /**
         * Audible's chapter marks (book positions) when they fit the files; else one chapter per file. They fit when there are any and the
         * Audible runtime is within [MAX_RUNTIME_MISMATCH] of the files' total (an estimate counts: it is derived from the catalog runtime).
         * The marks are used as they are (not stretched): a different intro or outro shifts everything, and which end is not knowable.
         */
        fun build(timeline: BookTimeline, audible: Chapters?): ChapterMap {
            val marks = audible?.chapters.orEmpty()
            val total = timeline.totalMs
            if (marks.isNotEmpty() && total > 0 && fits(timeline, audible!!, marks)) {
                val usable = normalize(marks, total)
                if (usable.isNotEmpty()) {
                    return ChapterMap(
                        usable.mapIndexed { i, c -> BookChapter(i, c.title.trim(), c.startMs, usable.getOrNull(i + 1)?.startMs ?: total) },
                        ChapterSource.Audible,
                    )
                }
            }
            return ofFiles(timeline)
        }

        fun ofFiles(timeline: BookTimeline): ChapterMap = ChapterMap(
            timeline.files.mapIndexed { i, f -> BookChapter(i, titleOfPath(f.path), timeline.startOf(i), timeline.startOf(i) + timeline.durationOf(i)) },
            ChapterSource.Files,
        )

        private fun fits(timeline: BookTimeline, audible: Chapters, marks: List<Chapter>): Boolean {
            val audibleTotal = if (audible.runtimeMs > 0) audible.runtimeMs else marks.maxOf { it.startMs + it.lengthMs }
            if (audibleTotal <= 0) return false
            return kotlin.math.abs(audibleTotal - timeline.totalMs) <= MAX_RUNTIME_MISMATCH * timeline.totalMs
        }

        /** Sorted, strictly increasing starts inside the book; the first chapter starts at 0. */
        private fun normalize(marks: List<Chapter>, total: Long): List<Chapter> {
            val sorted = marks.filter { it.startMs in 0 until total }.sortedBy { it.startMs }
            val out = ArrayList<Chapter>(sorted.size)
            for (c in sorted) if (out.isEmpty() || c.startMs > out.last().startMs) out.add(c)
            if (out.isNotEmpty() && out[0].startMs > 0) out[0] = out[0].copy(startMs = 0)
            return out
        }

        /** "Author/Part 03.m4b" -> "Part 03". */
        fun titleOfPath(path: String): String = path.substringAfterLast('/').substringBeforeLast('.').ifBlank { path }
    }
}
