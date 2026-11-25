package xyz.aresius.audiobooks.core.download

import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import xyz.aresius.audiobooks.core.data.ReleaseRepository
import xyz.aresius.audiobooks.core.network.TorrentFile
import xyz.aresius.audiobooks.core.torrent.PauseReason
import xyz.aresius.audiobooks.core.torrent.StallReason
import xyz.aresius.audiobooks.core.torrent.TorrentDownload
import xyz.aresius.audiobooks.core.torrent.TorrentEngine
import xyz.aresius.audiobooks.core.torrent.TorrentProgress

/**
 * BitTorrent on the phone through the [TorrentEngine] of core/torrent. Without an engine binding every run fails with
 * [FailReason.TorrentUnavailable] (the placeholder state until Phase 4 lands). The engine saves the selected files under the book
 * directory with their torrent-relative path (= our local path); a file counts as downloaded only when the engine lists it as complete
 * AND its size on disk matches what the metainfo says.
 */
class TorrentSource(
    private val engine: () -> TorrentEngine?,
    private val releases: ReleaseRepository? = null,
) : DownloadSource {

    override suspend fun run(ctx: JobContext) {
        val engine = engine() ?: throw DownloadException(FailReason.TorrentUnavailable, "BitTorrent is not available in this build")
        var planned = ctx.files()
        if (planned.isEmpty() || planned.any { it.state != FileState.COMPLETE && it.expectedSize <= 0 }) {
            ctx.setQueuedOrPreparing(null, null)
            val listed = try {
                engine.fetchFiles(ctx.releaseHash, ctx.magnet)
            } catch (e: IOException) {
                throw DownloadException(FailReason.NoSeeders, "No peers answered the metadata request", cause = e)
            } catch (e: TimeoutCancellationException) {
                throw DownloadException(FailReason.NoSeeders, "No peers answered the metadata request", cause = e)
            }
            val files = listed.map { TorrentFile(it.index, it.path, it.size) }
            if (planned.isEmpty() && releases != null) releases.reportTorrentFiles(ctx.releaseHash, files)
            planned = FilePlanner.matchProvider(ctx.userId, ctx.asin, ctx.releaseHash, ctx.collection, planned, files)
            ctx.planFiles(planned)
        }
        val pending = ctx.pendingFiles()
        if (pending.isEmpty()) return
        val byPath = pending.associateBy { it.path }
        val request = TorrentDownload(ctx.releaseHash, ctx.magnet, pending.map { it.path }, ctx.dir)
        var finished = false
        engine.download(request).collect { progress ->
            when (progress) {
                is TorrentProgress.Starting -> ctx.setQueuedOrPreparing(null, null)
                is TorrentProgress.Downloading -> {
                    ctx.setDownloading()
                    register(ctx, byPath, progress.completedPaths)
                    ctx.totals(progress.bytesDone, progress.bytesTotal, progress.downBytesPerSec, progress.etaSeconds)
                }
                is TorrentProgress.Finished -> {
                    register(ctx, byPath, progress.completedPaths)
                    finished = true
                }
                is TorrentProgress.Paused -> ctx.setPaused(
                    when (progress.reason) {
                        PauseReason.VPN_REQUIRED -> WaitReason.Vpn
                        PauseReason.WIFI_REQUIRED -> WaitReason.WiFi
                        PauseReason.LOW_BATTERY -> WaitReason.Battery
                        PauseReason.USER -> WaitReason.Network
                        PauseReason.SYSTEM -> WaitReason.Background
                    },
                )
                is TorrentProgress.Stalled -> throw DownloadException(
                    if (progress.reason == StallReason.NO_PEERS) FailReason.NoSeeders else FailReason.TorrentStalled,
                    "The torrent has stalled",
                )
                is TorrentProgress.Failed -> throw DownloadException(FailReason.Unknown, progress.message, retryable = true)
            }
        }
        if (!finished && ctx.pendingFiles().isNotEmpty()) throw DownloadException(FailReason.Unknown, "The torrent engine stopped before finishing", retryable = true)
    }

    private suspend fun register(ctx: JobContext, byPath: Map<String, DownloadFileEntity>, completed: Set<String>) {
        val done = ctx.files().filter { it.state == FileState.COMPLETE }.map { it.path }.toSet()
        for (path in completed - done) {
            val row = byPath[path] ?: continue
            val file = ctx.fileOf(row)
            val size = if (file.isFile) file.length() else -1L
            if (size <= 0 || (row.expectedSize > 0 && size != row.expectedSize)) {
                throw DownloadException(FailReason.Integrity, "A finished torrent file has the wrong size")
            }
            ctx.fileComplete(row, size)
        }
    }
}
