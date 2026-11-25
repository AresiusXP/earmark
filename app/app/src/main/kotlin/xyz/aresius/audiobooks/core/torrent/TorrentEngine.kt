package xyz.aresius.audiobooks.core.torrent

import java.io.File
import kotlinx.coroutines.flow.Flow

/** One file inside a torrent, in metainfo order (index = position in the torrent, NOT a debrid index). */
data class TorrentFileInfo(val index: Int, val path: String, val size: Long)

data class TorrentDownload(
    val infoHash: String,   // 40 lowercase hex
    val magnet: String,
    /** Paths (as listed by [TorrentEngine.fetchFiles]) to download, in the order they should be fetched. Others are never downloaded. */
    val selectedPaths: List<String>,
    /** Directory the selected files are saved under (their torrent-relative path is kept below it). */
    val destination: File,
)

sealed interface TorrentProgress {
    /** Fetching metadata / connecting. */
    data class Starting(val peers: Int) : TorrentProgress
    data class Downloading(
        val bytesDone: Long, val bytesTotal: Long, val downBytesPerSec: Long,
        val peers: Int, val seeds: Int, val etaSeconds: Long?,
        /** Paths of selected files that are complete on disk right now (playable). */
        val completedPaths: Set<String>,
    ) : TorrentProgress
    /** Every selected file is complete. */
    data class Finished(val completedPaths: Set<String>) : TorrentProgress
    /** No peer with the data for too long ("no seeders"), or stalled; the caller may keep waiting or cancel. */
    data class Stalled(val reason: StallReason, val bytesDone: Long) : TorrentProgress
    /** Paused by policy (VPN dropped, no Wi-Fi, low battery...). Resumes by itself when the policy allows again. */
    data class Paused(val reason: PauseReason) : TorrentProgress
    data class Failed(val message: String) : TorrentProgress
}

enum class StallReason { NO_PEERS, NO_PROGRESS }
enum class PauseReason { VPN_REQUIRED, WIFI_REQUIRED, LOW_BATTERY, USER }

/**
 * BitTorrent on the phone (Phase 4). The consumer is core/download. Everything here is opt-in by the USER
 * (settings.p2p_enabled after the warning); the engine enforces the safety policy itself, not only the UI.
 */
interface TorrentEngine {
    /** Downloads only the torrent metadata and lists its files. Throws on timeout (no peers). */
    suspend fun fetchFiles(infoHash: String, magnet: String): List<TorrentFileInfo>

    /** Cold flow: collecting starts/continues the download, cancelling the collector pauses it (state stays on disk). Completes after [TorrentProgress.Finished]. */
    fun download(request: TorrentDownload): Flow<TorrentProgress>

    /** Stops and removes the torrent from the session; [deleteFiles] also removes downloaded data. */
    suspend fun remove(infoHash: String, deleteFiles: Boolean)
}
