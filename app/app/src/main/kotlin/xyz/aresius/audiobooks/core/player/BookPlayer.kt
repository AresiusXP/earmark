package xyz.aresius.audiobooks.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import xyz.aresius.audiobooks.core.network.Book

/** What [BookPlayer] needs to know about the loaded book. */
class BookView(
    val book: Book,
    val timeline: BookTimeline,
    val chapters: ChapterMap,
    val skipBackMs: Long,
    val skipForwardMs: Long,
    val chapterTitle: (BookChapter) -> String,
)

/**
 * The player the media session sees: the book as a playlist of CHAPTERS over the one book timeline, with the ExoPlayer (one item per
 * file) hidden behind it. Positions are inside the current chapter, "next"/"previous" move between chapters (previous restarts a
 * chapter that is more than 3 s in, like a CD player), and skip back/forward cross chapter and file borders using the configured
 * skip length. The lock screen, headset buttons and Android Auto therefore speak chapters while the app's own screen shows the whole book.
 */
class BookPlayer(private val exo: ExoPlayer) : ForwardingSimpleBasePlayer(exo) {
    /** Set and read on the application (main) thread. */
    var view: BookView? = null
        set(value) {
            field = value
            invalidateState()
        }

    /** Re-reads the state (the chapter changed, durations were learned...). */
    fun refresh() = invalidateState()

    /** Position in the whole book of what the ExoPlayer plays now. */
    fun bookPositionMs(): Long {
        val v = view ?: return exo.currentPosition
        return v.timeline.toBook(exo.currentMediaItemIndex.coerceAtLeast(0), exo.currentPosition.coerceAtLeast(0))
    }

    fun seekToBook(bookMs: Long) {
        val v = view ?: return
        val at = v.timeline.toFile(bookMs)
        exo.seekTo(at.fileIndex, at.offsetMs)
    }

    override fun getState(): State {
        val base = super.getState()
        val v = view ?: return base
        val chapters = v.chapters.chapters
        if (chapters.isEmpty()) return base
        val index = v.chapters.indexAt(bookPositionMs()).coerceIn(0, chapters.lastIndex)
        val current = chapters[index]
        val items = chapters.map { c -> itemFor(v, c) }
        val commands = base.availableCommands.buildUpon()
            .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE)
            .addAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA)
            .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            .build()
        return base.buildUpon()
            .setPlaylist(items)
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs { (bookPositionMs() - current.startMs).coerceIn(0, current.lengthMs) }
            .setContentBufferedPositionMs {
                val buffered = v.timeline.toBook(exo.currentMediaItemIndex.coerceAtLeast(0), exo.bufferedPosition.coerceAtLeast(0))
                (buffered - current.startMs).coerceIn(0, current.lengthMs)
            }
            .setSeekBackIncrementMs(v.skipBackMs)
            .setSeekForwardIncrementMs(v.skipForwardMs)
            .setAvailableCommands(commands)
            .build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val v = view ?: return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        val chapters = v.chapters.chapters
        val target = when (seekCommand) {
            Player.COMMAND_SEEK_BACK -> bookPositionMs() - v.skipBackMs
            Player.COMMAND_SEEK_FORWARD -> bookPositionMs() + v.skipForwardMs
            else -> {
                val chapter = chapters.getOrNull(mediaItemIndex) ?: return Futures.immediateVoidFuture()
                chapter.startMs + if (positionMs == C.TIME_UNSET) 0 else positionMs
            }
        }
        seekToBook(target.coerceIn(0, v.timeline.totalMs))
        return Futures.immediateVoidFuture()
    }

    private fun itemFor(v: BookView, c: BookChapter): MediaItemData {
        val title = v.chapterTitle(c)
        val author = v.book.authors.joinToString(", ")
        val metadata = MediaMetadata.Builder()
            .setTitle(v.book.title)
            .setDisplayTitle(v.book.title)
            .setArtist(if (v.chapters.chapters.size > 1) title else author)
            .setSubtitle(if (v.chapters.chapters.size > 1) title else author)
            .setAlbumTitle(v.book.title)
            .setAlbumArtist(author)
            .setTrackNumber(c.index + 1)
            .setTotalTrackCount(v.chapters.chapters.size)
            .setArtworkUri(v.book.coverUrl?.let(Uri::parse))
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
        return MediaItemData.Builder("${v.book.asin}#${c.index}")
            .setMediaItem(MediaItem.Builder().setMediaId("${v.book.asin}#${c.index}").setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setDurationUs(c.lengthMs * 1000)
            .setIsSeekable(true)
            .build()
    }
}
