package xyz.aresius.audiobooks.core.torrent

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentPathsTest {
    @Test fun `normal audiobook paths pass`() {
        assertEquals("Book/01 - Chapter 1.mp3", TorrentPaths.safeRelative("Book/01 - Chapter 1.mp3"))
        assertEquals("Book.m4b", TorrentPaths.safeRelative("Book.m4b"))
        assertEquals("A: The Start/1.mp3", TorrentPaths.safeRelative("A: The Start/1.mp3"))
        assertEquals("Café/Ünï.mp3", TorrentPaths.safeRelative("Café/Ünï.mp3"))
        assertEquals("a/..hidden/.b..c", TorrentPaths.safeRelative("a/..hidden/.b..c"))
    }

    @Test fun `traversal, absolute and malformed paths are rejected`() {
        val bad = listOf(
            "", "..", "../x", "a/../../x", "a/..", "./a", "a/./b", "a//b", "a/", "/etc/passwd", "\\windows\\x", "a\\..\\b", "C:\\x", "C:/x",
            "~/x", "a/b\u0000c", "a/\nb", "...", "a/.../b", "x".repeat(256), "a/" + "y".repeat(300), "z".repeat(2000),
        )
        for (p in bad) assertNull(TorrentPaths.safeRelative(p), "should reject: ${p.take(40)}")
    }

    @Test fun `resolveUnder stays inside the destination`() {
        val dest = Files.createTempDirectory("dest").toFile()
        try {
            val f = assertNotNull(TorrentPaths.resolveUnder(dest, "Book/ch1.mp3"))
            assertTrue(f.path.startsWith(dest.canonicalPath + File.separator))
            assertNull(TorrentPaths.resolveUnder(dest, "../outside.txt"))
            assertNull(TorrentPaths.resolveUnder(dest, "/tmp/outside.txt"))
        } finally {
            dest.deleteRecursively()
        }
    }

    @Test fun `a symlink inside the destination pointing out is refused`() {
        val dest = Files.createTempDirectory("dest").toFile()
        val outside = Files.createTempDirectory("outside").toFile()
        try {
            Files.createSymbolicLink(File(dest, "link").toPath(), outside.toPath())
            assertNull(TorrentPaths.resolveUnder(dest, "link/evil.txt"))
            assertNotNull(TorrentPaths.resolveUnder(dest, "real/evil.txt"))
        } finally {
            dest.deleteRecursively(); outside.deleteRecursively()
        }
    }
}
