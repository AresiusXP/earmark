package xyz.aresius.audiobooks.core.download

import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.Provider

/** Why a book could not be downloaded (shown to the user; the server's own `reason` codes map onto the first ones). */
enum class FailReason {
    NoSeeders,
    DebridCooldown,
    DebridBusy,
    DebridFailed,
    NoDebridKey,
    BadDebridKey,

    /** 429: the add budget of the key is spent; [BookDownloadState.Failed.retryAfterSec] says when. */
    RateLimited,
    NotFound,
    NoSpace,
    StorageLimit,

    /** The downloaded size differs from the size the server reported, after retries. */
    Integrity,

    /** The server answered something that is not audio (an error page, a JSON body, a tiny placeholder). */
    InvalidContent,

    /** A server-provided file path is unsafe (absolute, `..`, control characters) or too long: nothing is downloaded. */
    InvalidPath,

    /** A multi-book pack whose files for this book are unknown: downloading it would fetch other books. */
    PackFilesUnknown,
    TorrentUnavailable,
    TorrentStalled,
    Network,
    Timeout,
    Unknown,
    ;

    val wire: String get() = name

    companion object {
        fun fromWire(value: String?): FailReason? = entries.firstOrNull { it.name == value }

        /** The `reason` of a `Prepare` answer. */
        fun fromPrepareReason(reason: String?): FailReason = when (reason) {
            "no_seeders" -> NoSeeders
            "debrid_cooldown" -> DebridCooldown
            "debrid_busy" -> DebridBusy
            "not_found" -> NotFound
            else -> DebridFailed
        }
    }
}

/** What a paused download waits for. */
/** [Background]: the system refused to run the download as a foreground service; WorkManager tries again. */
enum class WaitReason { WiFi, Network, Vpn, Battery, Background }

/** The per-book state other code observes (Book detail, Downloads screen, the player). */
sealed interface BookDownloadState {
    /** Nothing downloaded and nothing queued. */
    data object None : BookDownloadState

    data object Queued : BookDownloadState

    /** The debrid provider is getting the files ready ([percent] when it reports progress; [slowReason] a `Prepare.reason`). */
    data class Preparing(val percent: Int?, val slowReason: FailReason?) : BookDownloadState

    data class Downloading(
        val bytesDone: Long,
        val bytesTotal: Long,
        val speedBytesPerSec: Long,
        val etaSeconds: Long?,
        val filesDone: Int,
        val filesTotal: Int,
    ) : BookDownloadState {
        val fraction: Float get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
    }

    data class Paused(val waitingFor: WaitReason, val bytesDone: Long, val bytesTotal: Long) : BookDownloadState

    data class Failed(val reason: FailReason, val message: String?, val retryAfterSec: Int?) : BookDownloadState

    data class Completed(val bytes: Long) : BookDownloadState

    val isActive: Boolean get() = this is Queued || this is Preparing || this is Downloading || this is Paused
    val isCompleted: Boolean get() = this is Completed
}

/** Where the files of a release come from. */
sealed interface DownloadChoice {
    data class Debrid(val provider: Provider) : DownloadChoice
    data object Torrent : DownloadChoice

    val wire: String get() = when (this) {
        is Debrid -> provider.wire
        Torrent -> TORRENT_SOURCE
    }

    companion object {
        const val TORRENT_SOURCE = "torrent"

        fun fromWire(value: String): DownloadChoice? =
            if (value == TORRENT_SOURCE) Torrent else Provider.fromWire(value)?.let(::Debrid)
    }
}

sealed interface EnqueueResult {
    data object Started : EnqueueResult
    data object AlreadyDownloaded : EnqueueResult
    data object AlreadyActive : EnqueueResult
    data object NotSignedIn : EnqueueResult

    /** No debrid key and BitTorrent is not usable for this release. */
    data object NeedsKey : EnqueueResult
    data class Rejected(val reason: FailReason) : EnqueueResult
}

/** One book of the Downloads screen. */
data class DownloadedBook(val book: Book, val releaseHash: String, val state: BookDownloadState, val bytes: Long, val completedAtMs: Long?)

data class StorageInfo(val usedBytes: Long, val limitBytes: Long, val freeBytes: Long)

/** What the player may tell us: the book playing right now is never evicted. */
interface PlayingBook {
    fun currentAsin(): String?
}

object NoPlayingBook : PlayingBook {
    override fun currentAsin(): String? = null
}

/** Library facts the storage rules need: when each book of [userId] was finished (ms), for LRU eviction and auto-delete. */
fun interface FinishedBooks {
    suspend fun finishedAtMs(userId: String): Map<String, Long>
}
