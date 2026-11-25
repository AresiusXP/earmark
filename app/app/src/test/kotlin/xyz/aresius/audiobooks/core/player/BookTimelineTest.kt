package xyz.aresius.audiobooks.core.player

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BookTimelineTest {
    private fun file(i: Int, size: Long = 1_000, duration: Long? = null) = BookFile("part$i.mp3", i, size, duration)

    @Test
    fun `known durations map positions exactly`() {
        val t = BookTimeline(listOf(file(0, duration = 1000), file(1, duration = 2000), file(2, duration = 500)), runtimeMs = 3500)
        assertEquals(3500, t.totalMs)
        assertTrue(t.exact)
        assertEquals(FilePosition(0, 0), t.toFile(0))
        assertEquals(FilePosition(0, 999), t.toFile(999))
        assertEquals(FilePosition(1, 0), t.toFile(1000))
        assertEquals(FilePosition(2, 499), t.toFile(3499))
        assertEquals(FilePosition(2, 500), t.toFile(3500))
        assertEquals(1000, t.toBook(1, 0))
        assertEquals(1500, t.toBook(1, 500))
    }

    @Test
    fun `positions are clamped to the book`() {
        val t = BookTimeline(listOf(file(0, duration = 1000), file(1, duration = 1000)), 2000)
        assertEquals(FilePosition(0, 0), t.toFile(-5))
        assertEquals(FilePosition(1, 1000), t.toFile(99_999))
        assertEquals(1000, t.toBook(0, 5_000))
        assertEquals(0, t.toBook(1, -3) - 1000)
    }

    @Test
    fun `unknown durations share the rest of the catalog runtime by size`() {
        val t = BookTimeline(listOf(file(0, size = 100, duration = 1000), file(1, size = 100), file(2, size = 300)), runtimeMs = 5000)
        assertFalse(t.exact)
        assertEquals(1000, t.durationOf(0))
        assertEquals(1000, t.durationOf(1))
        assertEquals(3000, t.durationOf(2))
        assertEquals(5000, t.totalMs)
    }

    @Test
    fun `without a runtime the size and an assumed bitrate give the estimate`() {
        val t = BookTimeline(listOf(file(0, size = 8_000)), runtimeMs = 0)
        assertEquals(8_000 * 8_000 / BookTimeline.ASSUMED_BITRATE_BPS, t.totalMs)
    }

    @Test
    fun `learning a duration improves the timeline and keeps the instance when nothing changes`() {
        val t = BookTimeline(listOf(file(0), file(1)), runtimeMs = 2000)
        val learned = t.withDuration(0, 700)
        assertEquals(700, learned.durationOf(0))
        assertEquals(1300, learned.durationOf(1))
        assertEquals(2000, learned.totalMs)
        assertSame(learned, learned.withDuration(0, 700))
        assertSame(t, t.withDuration(9, 5))
        assertTrue(learned.withDuration(1, 1300).exact)
    }

    @Test
    fun `a known sum above the runtime still gives every unknown file a length`() {
        val t = BookTimeline(listOf(file(0, duration = 9000), file(1, size = 64_000)), runtimeMs = 5000)
        assertTrue(t.durationOf(1) > 0)
        assertEquals(t.durationOf(0) + t.durationOf(1), t.totalMs)
    }

    @Test
    fun `property - mapping round trips and is monotonic for any book`() {
        val random = Random(42)
        repeat(300) {
            val n = random.nextInt(1, 40)
            val files = (0 until n).map { i ->
                file(i, size = random.nextLong(1, 5_000_000), duration = if (random.nextInt(3) == 0) null else random.nextLong(1, 4_000_000))
            }
            val runtime = if (random.nextBoolean()) random.nextLong(0, 100_000_000) else 0
            val t = BookTimeline(files, runtime)
            assertEquals((0 until n).sumOf { t.durationOf(it) }, t.totalMs)
            var previous = -1L
            repeat(60) {
                val x = random.nextLong(0, t.totalMs + 1)
                val at = t.toFile(x)
                assertTrue(at.fileIndex in 0 until n)
                assertTrue(at.offsetMs in 0..t.durationOf(at.fileIndex))
                assertEquals(x, t.toBook(at.fileIndex, at.offsetMs), "round trip of $x")
                if (x >= previous) assertTrue(t.toFile(x).fileIndex >= t.toFile(previous.coerceAtLeast(0)).fileIndex)
                previous = x
            }
            for (i in 0 until n) {
                val start = t.startOf(i)
                assertEquals(FilePosition(i, 0), t.toFile(start), "start of file $i")
                if (t.durationOf(i) > 1) assertEquals(FilePosition(i, t.durationOf(i) - 1), t.toFile(start + t.durationOf(i) - 1))
            }
        }
    }

    @Test
    fun `property - learning durations one by one converges to the exact timeline`() {
        val random = Random(7)
        repeat(100) {
            val real = List(random.nextInt(1, 15)) { random.nextLong(1_000, 3_000_000) }
            var t = BookTimeline(real.indices.map { file(it, size = real[it] / 10) }, runtimeMs = real.sum() + random.nextLong(-5_000, 5_000).coerceAtLeast(0))
            real.indices.shuffled(random).forEach { i -> t = t.withDuration(i, real[i]) }
            assertTrue(t.exact)
            assertEquals(real.sum(), t.totalMs)
        }
    }
}
