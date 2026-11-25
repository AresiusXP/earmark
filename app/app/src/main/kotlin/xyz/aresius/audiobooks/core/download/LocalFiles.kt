package xyz.aresius.audiobooks.core.download

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * What the player needs from the download layer: a fully downloaded local copy of a book file, if any.
 * Contract between the player (consumer) and core/download (implementation); do not widen it without
 * telling both sides. Files are identified by PATH (the debrid and torrent file indexes differ), as in the API.
 */
interface LocalFiles {
    /** The complete local file for [path] of release [releaseHash] of book [asin], or null (never a partial file). */
    fun localFile(asin: String, releaseHash: String, path: String): File?

    /** Emits true while at least one file of the book is stored locally (to show "downloaded" badges). */
    fun hasDownloads(asin: String): Flow<Boolean>
}

/** Default binding until core/download provides the real implementation. */
object NoLocalFiles : LocalFiles {
    override fun localFile(asin: String, releaseHash: String, path: String): File? = null
    override fun hasDownloads(asin: String): Flow<Boolean> = flowOf(false)
}
