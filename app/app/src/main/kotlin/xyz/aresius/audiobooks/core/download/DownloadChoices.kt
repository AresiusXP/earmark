package xyz.aresius.audiobooks.core.download

import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.ReleaseView

object DownloadChoices {
    /**
     * How to get [release] onto the phone: a debrid provider the user has a working key for (one that has it cached first, then the
     * user's preferred one), else BitTorrent when the user turned it on, else null ("add a key in Settings").
     */
    fun choose(release: ReleaseView, preferredProvider: String? = null): DownloadChoice? {
        val providers = release.usable.debrid.mapNotNull(Provider::fromWire).distinct()
        val provider = providers.firstOrNull { release.cached[it.wire] == true }
            ?: providers.firstOrNull { it.wire == preferredProvider }
            ?: providers.firstOrNull()
        return when {
            provider != null -> DownloadChoice.Debrid(provider)
            release.usable.p2p -> DownloadChoice.Torrent
            else -> null
        }
    }
}

/** Pure mapping from the stored job to what the UI shows. */
object DownloadStateLogic {
    fun derive(book: DownloadBookEntity?, files: List<DownloadFileEntity>, live: LiveProgress?, net: NetKind, allowMobileData: Boolean): BookDownloadState {
        if (book == null) return BookDownloadState.None
        val onDisk = files.sumOf { if (it.state == FileState.COMPLETE) it.size else it.bytesDone }
        val total = if (files.isNotEmpty() && files.all { it.expectedSize > 0 }) files.sumOf { it.expectedSize } else book.sizeHint
        val done = live?.bytesDone ?: onDisk
        return when (book.state) {
            JobState.COMPLETED -> BookDownloadState.Completed(files.sumOf { it.size })
            JobState.FAILED -> BookDownloadState.Failed(FailReason.fromWire(book.failReason) ?: FailReason.Unknown, book.failMessage, book.retryAfterSec)
            else -> {
                val gate = DownloadGate.check(net, allowMobileData, book.unmeteredOnly)
                when {
                    book.state == JobState.USER_PAUSED -> BookDownloadState.Paused(WaitReason.User, done, live?.bytesTotal ?: total)
                    gate is Gate.Wait -> BookDownloadState.Paused(gate.reason, done, live?.bytesTotal ?: total)
                    book.state == JobState.PAUSED -> BookDownloadState.Paused(WaitReason.entries.firstOrNull { it.name == book.pauseReason } ?: WaitReason.Network, done, live?.bytesTotal ?: total)
                    book.state == JobState.QUEUED -> BookDownloadState.Queued
                    book.state == JobState.PREPARING -> BookDownloadState.Preparing(book.preparePercent, FailReason.fromWire(book.slowReason))
                    else -> BookDownloadState.Downloading(
                        done, live?.bytesTotal ?: total, live?.speedBytesPerSec ?: 0, live?.etaSeconds,
                        files.count { it.state == FileState.COMPLETE }, files.size,
                    )
                }
            }
        }
    }
}
