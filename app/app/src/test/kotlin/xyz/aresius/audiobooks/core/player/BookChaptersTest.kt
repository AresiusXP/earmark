package xyz.aresius.audiobooks.core.player

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.aresius.audiobooks.core.network.Chapter
import xyz.aresius.audiobooks.core.network.Chapters

class BookChaptersTest {
    private fun timeline(vararg durations: Long) =
        BookTimeline(durations.mapIndexed { i, d -> BookFile("Book/Part ${i + 1}.mp3", i, 1000, d) }, durations.sum())

    private fun audible(runtime: Long, vararg starts: Long): Chapters {
        val all = starts.toList()
        return Chapters("B1", runtime, all.mapIndexed { i, s -> Chapter("Ch ${i + 1}", s, (all.getOrNull(i + 1) ?: runtime) - s) })
    }

    @Test
    fun `audible chapters are used when the runtime fits`() {
        val map = ChapterMap.build(timeline(600_000, 400_000), audible(1_000_000, 0, 250_000, 600_000))
        assertEquals(ChapterSource.Audible, map.source)
        assertEquals(listOf(0L, 250_000L, 600_000L), map.chapters.map { it.startMs })
        assertEquals(listOf(250_000L, 600_000L, 1_000_000L), map.chapters.map { it.endMs })
        assertEquals("Ch 2", map.chapters[1].title)
    }

    @Test
    fun `within 2 percent still counts as fitting, beyond it falls back to one chapter per file`() {
        val files = timeline(600_000, 400_000)
        assertEquals(ChapterSource.Audible, ChapterMap.build(files, audible(1_019_000, 0, 500_000)).source)
        assertEquals(ChapterSource.Audible, ChapterMap.build(files, audible(981_000, 0, 500_000)).source)
        val off = ChapterMap.build(files, audible(1_030_000, 0, 500_000))
        assertEquals(ChapterSource.Files, off.source)
        assertEquals(listOf("Part 1", "Part 2"), off.chapters.map { it.title })
        assertEquals(listOf(0L, 600_000L), off.chapters.map { it.startMs })
    }

    @Test
    fun `no or unusable chapters fall back to the files`() {
        val files = timeline(100, 200, 300)
        assertEquals(ChapterSource.Files, ChapterMap.build(files, null).source)
        assertEquals(ChapterSource.Files, ChapterMap.build(files, Chapters("B1", 600, emptyList())).source)
        assertEquals(3, ChapterMap.build(files, null).chapters.size)
        assertEquals(600, ChapterMap.build(files, null).chapters.last().endMs)
    }

    @Test
    fun `marks are sorted, deduplicated and clipped to the book and the first starts at zero`() {
        val map = ChapterMap.build(
            timeline(1000),
            Chapters("B1", 1000, listOf(Chapter("b", 500, 100), Chapter("a", 100, 100), Chapter("dup", 500, 10), Chapter("late", 5000, 10))),
        )
        assertEquals(listOf(0L, 500L), map.chapters.map { it.startMs })
        assertEquals(listOf("a", "b"), map.chapters.map { it.title })
        assertEquals(1000, map.chapters.last().endMs)
    }

    @Test
    fun `an estimated timeline compares against the catalog runtime it was built from`() {
        val estimated = BookTimeline(listOf(BookFile("a.mp3", 0, 100), BookFile("b.mp3", 1, 100)), runtimeMs = 1_000_000)
        assertEquals(ChapterSource.Audible, ChapterMap.build(estimated, audible(1_000_000, 0, 400_000)).source)
    }

    @Test
    fun `navigation like a CD player`() {
        val map = ChapterMap.build(timeline(1_000_000), audible(1_000_000, 0, 300_000, 700_000))
        assertEquals(0, map.indexAt(0))
        assertEquals(1, map.indexAt(300_000))
        assertEquals(2, map.indexAt(5_000_000))
        assertEquals(300_000L, map.nextStart(10))
        assertNull(map.nextStart(800_000))
        assertEquals(300_000L, map.previousTarget(310_000), "within 3 s of the start: the previous chapter")
        assertEquals(0L, map.previousTarget(302_000))
        assertEquals(300_000L, map.previousTarget(400_000), "later in the chapter: its own start")
        assertEquals(0L, map.previousTarget(100))
    }

    @Test
    fun `property - chapters always tile the book without gaps`() {
        val random = Random(11)
        repeat(300) {
            val files = List(random.nextInt(1, 12)) { random.nextLong(1_000, 5_000_000) }
            val t = timeline(*files.toLongArray())
            val marks = List(random.nextInt(0, 40)) { random.nextLong(-100, t.totalMs + 500) }
            val runtime = (t.totalMs * (0.97 + random.nextDouble() * 0.06)).toLong()
            val map = ChapterMap.build(t, Chapters("B", runtime, marks.map { Chapter("m", it, 1) }))
            assertTrue(map.chapters.isNotEmpty())
            assertEquals(0, map.chapters.first().startMs)
            assertEquals(t.totalMs, map.chapters.last().endMs)
            map.chapters.zipWithNext().forEach { (a, b) ->
                assertEquals(a.endMs, b.startMs)
                assertTrue(a.startMs < a.endMs)
            }
            val x = random.nextLong(0, t.totalMs)
            val c = map.at(x)!!
            assertTrue(x >= c.startMs && x < c.endMs, "$x in $c")
        }
    }

    // --- contract v1.1: Audible branding (intro_ms / outro_ms) -------------------------------------------------------

    private fun withBranding(runtime: Long, intro: Long?, outro: Long?, vararg starts: Long) = audible(runtime, *starts).copy(introMs = intro, outroMs = outro)

    @Test
    fun `a release that cut the intro moves the marks earlier by it`() {
        // Audible: 1_000_000 with 10 s intro; the files are 990_000 long.
        val map = ChapterMap.build(timeline(990_000), withBranding(1_000_000, 10_000, null, 0, 10_000, 410_000, 810_000))
        assertEquals(ChapterSource.Audible, map.source)
        assertEquals(listOf(0L, 400_000L, 800_000L), map.chapters.map { it.startMs }, "the intro's own mark is gone and the next one starts at 0")
        assertEquals(990_000L, map.chapters.last().endMs)
    }

    @Test
    fun `a release that kept the branding leaves the marks where they are`() {
        val map = ChapterMap.build(timeline(1_000_000), withBranding(1_000_000, 10_000, 8_000, 0, 10_000, 410_000))
        assertEquals(listOf(0L, 10_000L, 410_000L), map.chapters.map { it.startMs })
    }

    @Test
    fun `a release that cut only the outro does not move the marks`() {
        val map = ChapterMap.build(timeline(992_000), withBranding(1_000_000, 10_000, 8_000, 0, 10_000, 410_000, 995_000))
        assertEquals(listOf(0L, 10_000L, 410_000L), map.chapters.map { it.startMs }, "a mark in the cut outro is clipped")
        assertEquals(992_000L, map.chapters.last().endMs)
    }

    @Test
    fun `a release that cut both ends shifts by the intro and drops the outro marks`() {
        val map = ChapterMap.build(timeline(982_000), withBranding(1_000_000, 10_000, 8_000, 0, 10_000, 500_000, 995_000))
        assertEquals(ChapterSource.Audible, map.source)
        assertEquals(listOf(0L, 490_000L), map.chapters.map { it.startMs })
    }

    @Test
    fun `a mark that lies wholly inside the cut intro is dropped`() {
        val chapters = Chapters("B1", 100_000, listOf(Chapter("This is Audible", 0, 4_000), Chapter("One", 4_000, 46_000), Chapter("Two", 50_000, 50_000)), introMs = 5_000)
        val map = ChapterMap.build(timeline(95_000), chapters)
        assertEquals(listOf("One", "Two"), map.chapters.map { it.title })
        assertEquals(listOf(0L, 45_000L), map.chapters.map { it.startMs })
    }

    @Test
    fun `the two percent rule is measured on the trimmed total too`() {
        // Trimmed Audible total 1_000_000 - 100_000 - 100_000 = 800_000: files of 810_000 fit (1.2 %), files of 830_000 (3.7 %) do not.
        val a = withBranding(1_000_000, 100_000, 100_000, 0, 400_000)
        assertEquals(ChapterSource.Audible, ChapterMap.build(timeline(810_000), a).source)
        assertEquals(100_000L, ChapterMap.align(810_000, a, a.chapters))
        assertEquals(ChapterSource.Files, ChapterMap.build(timeline(830_000), a).source)
        assertNull(ChapterMap.align(830_000, a, a.chapters))
    }

    @Test
    fun `without branding data nothing changes, absent and zero alike`() {
        for (branding in listOf(null to null, 0L to 0L, 0L to null)) {
            val chapters = withBranding(1_000_000, branding.first, branding.second, 0, 250_000)
            assertEquals(0L, ChapterMap.align(1_000_000, chapters, chapters.chapters))
            assertEquals(0L, ChapterMap.align(985_000, chapters, chapters.chapters))
            assertNull(ChapterMap.align(900_000, chapters, chapters.chapters))
        }
    }

    @Test
    fun `align picks the closest of the four possible lengths and a tie prefers the untouched total`() {
        val c = withBranding(1_000_000, 20_000, 10_000, 0, 500_000)
        assertEquals(0L, ChapterMap.align(1_000_000, c, c.chapters))
        assertEquals(20_000L, ChapterMap.align(980_000, c, c.chapters), "intro cut")
        assertEquals(0L, ChapterMap.align(990_000, c, c.chapters), "outro cut: no shift")
        assertEquals(20_000L, ChapterMap.align(970_000, c, c.chapters), "both cut")
        val tie = withBranding(1_000, 10, 0, 0)
        assertEquals(0L, ChapterMap.align(995, tie, tie.chapters))
    }
}
