package xyz.aresius.audiobooks.core.torrent

import kotlinx.coroutines.flow.StateFlow

/** Thrown by [TorrentEngine.fetchFiles] when the policy does not allow BitTorrent right now (opt-in off, no VPN, no Wi-Fi, low battery). */
class TorrentBlockedException(val decision: PolicyDecision) : IllegalStateException(
    when (decision) {
        PolicyDecision.Disabled -> "BitTorrent is turned off"
        is PolicyDecision.Blocked -> "BitTorrent is paused by policy: ${decision.reason}"
        PolicyDecision.Allowed -> "BitTorrent is allowed"
    },
)

/** The torrent or a path in it is unsafe (traversal, absolute path, symlink) or the metadata does not match the request. */
class TorrentRejectedException(message: String) : IllegalArgumentException(message)

/** Aggregated state of everything the engine is doing, for the foreground service notification. */
data class EngineActivity(
    /** Downloads being collected (running, waiting for a slot, or paused by policy). */
    val downloads: Int = 0,
    val downBytesPerSec: Long = 0,
    val upBytesPerSec: Long = 0,
    val peers: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val etaSeconds: Long? = null,
    /** Non-null while every torrent is paused (policy or user). */
    val paused: PauseReason? = null,
    /** Finished torrents kept in the session to seed to ratio 1.0 (opt-in, Wi-Fi + charging only). */
    val seeding: Int = 0,
) {
    val idle: Boolean get() = downloads == 0 && seeding == 0
}

/** What the foreground service and the WorkManager resume job need from the engine; implemented by [LibTorrentEngine]. */
interface TorrentRuntime {
    val activity: StateFlow<EngineActivity>

    /** Notification "Pause" / "Resume": pauses (or resumes) every torrent. */
    fun setUserPaused(paused: Boolean)

    /** Notification "Stop": pauses every torrent; collectors see Paused(USER) and decide what to do. */
    fun stopAll()

    /** Android 15 caps dataSync foreground services at 6 h per 24 h: pause when the system says so. */
    fun suspendForTimeLimit()

    /** The system refused to run the foreground service (background-start limits, used-up budget): pause (`Paused(SYSTEM)`) until it can run. */
    fun suspendForForegroundRefusal()

    /** True when [suspendForTimeLimit] or [suspendForForegroundRefusal] paused things and [resumeAfterTimeLimit] has not run yet. */
    val suspendedForTimeLimit: Boolean

    fun resumeAfterTimeLimit()
}

/**
 * Starts the foreground service that keeps the process alive while torrents run; a no-op in tests.
 * Throws when the system refuses: the engine then pauses everything instead of running unprotected in the background.
 */
fun interface ServiceLauncher {
    fun ensureRunning()
}

/** Session-level knobs that differ between the app and the instrumented tests. */
data class EngineConfig(
    val listenInterfaces: String = "0.0.0.0:0,[::]:0",
    val enableDht: Boolean = true,
    val enableLsd: Boolean = true,
    val enableUtp: Boolean = true,
    val enablePortMaps: Boolean = true,
    val metadataTimeoutMs: Long = 60_000,
    /** The session is stopped this long after the last torrent stopped (a quick Pause/Resume does not restart it). */
    val idleStopMs: Long = 15_000,
    val resumeSaveEveryMs: Long = 30_000,
    val tickMs: Long = 1_000,
    val stallNoPeersMs: Long = 3 * 60_000L,
    val stallNoProgressMs: Long = 5 * 60_000L,
    val observeProcessLifecycle: Boolean = true,
)

/** One line for logs and the debug screen. */
fun formatProgress(p: TorrentProgress): String = when (p) {
    is TorrentProgress.Starting -> "Starting, ${p.peers} peers"
    is TorrentProgress.Downloading ->
        "${p.bytesDone / 1024} / ${p.bytesTotal / 1024} KiB, ${p.downBytesPerSec / 1024} KiB/s, ${p.peers} peers (${p.seeds} seeds), " +
            "eta ${p.etaSeconds ?: "?"} s, done files: ${p.completedPaths.size}"
    is TorrentProgress.Finished -> "Finished: ${p.completedPaths.size} files"
    is TorrentProgress.Stalled -> "Stalled (${p.reason}) at ${p.bytesDone / 1024} KiB"
    is TorrentProgress.Paused -> "Paused: ${p.reason}"
    is TorrentProgress.Failed -> "Failed: ${p.message}"
}
