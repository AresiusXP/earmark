package xyz.aresius.audiobooks.core.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.Chapter
import xyz.aresius.audiobooks.core.network.Chapters

/**
 * The media session's view of a book, on a real ExoPlayer with three 2-second WAV files (6 s) and two Audible chapters that do NOT line up
 * with the files (0-3 s, 3-6 s): chapters as the playlist, positions inside the chapter, next/previous between chapters, skip crossing file
 * and chapter borders, and playback that runs across a file border.
 */
@RunWith(AndroidJUnit4::class)
class BookPlayerTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var exo: ExoPlayer
    private lateinit var player: BookPlayer
    private val book = Book(asin = "B1", title = "The Book", authors = listOf("An Author"), runtimeMin = 0, coverUrl = "http://example.test/c.png")

    @Before fun setUp() {
        val files = (0..2).map { writeWav(context.cacheDir.resolve("bp-$it.wav"), 2_000) }
        val timeline = BookTimeline(files.mapIndexed { i, f -> BookFile(f.name, i, f.length(), 2_000L) }, 6_000)
        val chapters = ChapterMap.build(timeline, Chapters("B1", 6_000, listOf(Chapter("One", 0, 3_000), Chapter("Two", 3_000, 3_000))))
        assertEquals(ChapterSource.Audible, chapters.source)
        onMain {
            exo = ExoPlayer.Builder(context).build()
            player = BookPlayer(exo)
            exo.setMediaItems(files.map { MediaItem.fromUri(Uri.fromFile(it)) })
            exo.prepare()
            player.view = BookView(book, timeline, chapters, skipBackMs = 1_000, skipForwardMs = 1_000) { it.title }
        }
        awaitMain(message = "ready") { exo.playbackState == Player.STATE_READY && player.currentTimeline.windowCount == 2 }
    }

    @After fun tearDown() = onMain {
        player.release()
        exo.release()
    }

    private fun bookPosition() = onMain { player.bookPositionMs() }

    @Test fun chaptersAreThePlaylistAndMetadataIsTheBook() = onMain {
        assertEquals(2, player.mediaItemCount)
        assertEquals("The Book", player.mediaMetadata.title)
        assertEquals("One", player.mediaMetadata.artist)
        assertEquals(3_000L, player.duration)
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_BACK))
        assertTrue(!player.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM), "controllers cannot replace the book's items")
    }

    @Test fun seekingAcrossFilesUsesTheBookTimeline() {
        onMain { player.seekToBook(2_500) }
        awaitMain { exo.currentMediaItemIndex == 1 }
        assertNear(2_500L, bookPosition(), 150)
        onMain { player.seekToBook(4_000) }
        awaitMain { player.currentMediaItemIndex == 1 }
        onMain { assertNear(1_000L, player.currentPosition, 150) }
    }

    @Test fun nextAndPreviousMoveBetweenChaptersNotFiles() {
        onMain { player.seekToNext() }
        awaitMain { player.currentMediaItemIndex == 1 }
        assertNear(3_000L, bookPosition(), 150)
        onMain { player.seekToBook(5_500) }
        awaitMain { player.bookPositionMs() > 5_000 }
        onMain { player.seekToPrevious() }
        awaitMain { player.currentMediaItemIndex == 0 }
        assertNear(0L, bookPosition(), 150)
    }

    @Test fun skipBackCrossesFileAndChapterBorders() {
        onMain { player.seekToBook(3_200) }
        awaitMain { player.currentMediaItemIndex == 1 }
        onMain { player.seekBack() }
        awaitMain { player.currentMediaItemIndex == 0 }
        assertNear(2_200L, bookPosition(), 150)
        onMain { player.seekForward() }
        awaitMain { player.currentMediaItemIndex == 1 }
        assertNear(3_200L, bookPosition(), 200)
    }

    @Test fun playbackRunsAcrossAFileBorderWithAMonotonicBookPosition() {
        onMain {
            player.seekToBook(1_600)
            player.play()
        }
        var last = 0L
        val deadline = System.currentTimeMillis() + 8_000
        var crossed = false
        while (System.currentTimeMillis() < deadline && !crossed) {
            val now = bookPosition()
            assertTrue(now >= last - 120, "position went back: $last -> $now")
            last = now
            crossed = onMain { exo.currentMediaItemIndex } >= 1 && now > 2_300
            Thread.sleep(40)
        }
        assertTrue(crossed, "never got past the file border (last=$last)")
    }
}
