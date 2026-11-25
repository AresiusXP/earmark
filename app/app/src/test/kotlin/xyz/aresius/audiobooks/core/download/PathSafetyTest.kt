package xyz.aresius.audiobooks.core.download

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathSafetyTest {
    @Test fun acceptsPlainRelativePaths() {
        assertEquals("Book/Part 01.mp3", PathSafety.sanitize("Book/Part 01.mp3"))
        assertEquals("Hoofdstuk één – De Reis.m4b", PathSafety.sanitize("Hoofdstuk één – De Reis.m4b"))
        assertEquals("a..b/c.mp3", PathSafety.sanitize("a..b/c.mp3"), "dots inside a name are fine")
    }

    @Test fun rejectsTraversalAbsoluteAndOddPaths() {
        listOf(
            "", "../evil.mp3", "a/../../evil.mp3", "a/..", "..", ".", "./a.mp3", "a/./b.mp3", "/etc/passwd", "//share/x.mp3",
            "a//b.mp3", "a/", "C:/Windows/x.mp3", "c:x.mp3", "a\\..\\b.mp3", "a\\b.mp3", "a\u0000b.mp3", "a\nb.mp3", "x.mp3.part", "dir/x.part",
        ).forEach { assertNull(PathSafety.sanitize(it), "must be rejected: ${it.replace("\u0000", "\\0")}") }
    }

    @Test fun rejectsTooLongPathsAndSegments() {
        assertNull(PathSafety.sanitize("a".repeat(PathSafety.MAX_SEGMENT_BYTES + 1) + ".mp3"))
        assertNull(PathSafety.sanitize((1..80).joinToString("/") { "segment$it" } + "/file.mp3"))
        assertNotNull(PathSafety.sanitize("a".repeat(PathSafety.MAX_SEGMENT_BYTES - 4) + ".mp3"))
    }

    @Test fun resolveStaysInsideTheRoot() {
        val root = Files.createTempDirectory("root").toFile()
        try {
            assertEquals(root.canonicalPath + "/b/c.mp3", PathSafety.resolve(root, "b/c.mp3")!!.canonicalPath)
            assertNull(PathSafety.resolve(root, "../x.mp3"))
            assertNull(PathSafety.resolve(root, "/tmp/x.mp3"))
            // A symlink inside the root that points outside must not let a path escape through it.
            val outside = Files.createTempDirectory("outside").toFile()
            Files.createSymbolicLink(root.toPath().resolve("link"), outside.toPath())
            assertNull(PathSafety.resolve(root, "link/x.mp3"))
            outside.deleteRecursively()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun idsBecomeDirectoryNamesOnlyWhenAlphanumeric() {
        assertTrue(PathSafety.isSafeId("B000000001"))
        assertTrue(PathSafety.isSafeId("a".repeat(40)))
        assertFalse(PathSafety.isSafeId("../x"))
        assertFalse(PathSafety.isSafeId(""))
        assertFalse(PathSafety.isSafeId("a/b"))
    }

    @Test fun matchKeyIgnoresLeadingSlashes() {
        assertEquals(PathSafety.matchKey("/Book/a.mp3"), PathSafety.matchKey("Book/a.mp3"))
    }

    @Test fun audioFilesAreRecognisedByExtension() {
        assertTrue(PathSafety.isAudio("x/Part 1.MP3"))
        assertTrue(PathSafety.isAudio("book.m4b"))
        assertFalse(PathSafety.isAudio("cover.jpg"))
        assertFalse(PathSafety.isAudio("readme"))
    }

    @Test fun naturalOrderPutsTrack2BeforeTrack10() {
        val sorted = listOf("Track 10.mp3", "track 2.mp3", "Track 1.mp3").sortedWith { a, b -> FilePlanner.naturalCompare(a, b) }
        assertEquals(listOf("Track 1.mp3", "track 2.mp3", "Track 10.mp3"), sorted)
    }
}
