package xyz.aresius.audiobooks.core.download

import kotlinx.coroutines.delay
import xyz.aresius.audiobooks.core.data.DownloadLink
import xyz.aresius.audiobooks.core.data.ReleaseRepository
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.ErrorCode
import xyz.aresius.audiobooks.core.network.Prepare
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.TorrentFile

/**
 * Debrid: `prepare` until the provider has the files, then per file `GET /v1/releases/{hash}/files/{index}/download` -> 302 -> the CDN
 * (fetched without our credentials by [fetcher]). A fresh link is requested immediately before every file and never stored. The
 * 202 placeholder and error bodies are never written as audio ([HttpFileFetcher] only accepts 200/206 audio).
 */
class DebridSource(
    private val releases: ReleaseRepository,
    private val fetcher: HttpFileFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : DownloadSource {

    override suspend fun run(ctx: JobContext) {
        val provider = Provider.fromWire(ctx.source) ?: throw DownloadException(FailReason.Unknown, "Unknown provider")
        val planned = ctx.files()
        if (planned.isEmpty() || planned.any { it.state != FileState.COMPLETE }) {
            val files = prepare(ctx, provider)
            val rows = FilePlanner.matchProvider(ctx.userId, ctx.asin, ctx.releaseHash, ctx.collection, planned, files)
            ctx.planFiles(rows)
        }
        for (file in ctx.pendingFiles()) downloadFile(ctx, provider, file)
    }

    private suspend fun prepare(ctx: JobContext, provider: Provider): List<TorrentFile> {
        val since = ctx.job.preparingSinceMs ?: clock()
        while (true) {
            when (val r = releases.prepare(ctx.releaseHash, provider, ctx.magnet.takeIf { it.isNotBlank() })) {
                is ApiResult.Failure -> throw r.error.toDownloadException()
                is ApiResult.Success -> {
                    val p = r.value
                    when (p.state) {
                        Prepare.READY -> return p.files
                        Prepare.FAILED -> throw DownloadException(FailReason.fromPrepareReason(p.reason), "The provider could not prepare this release (${p.reason ?: "failed"})")
                        else -> {
                            if (clock() - since > PREPARE_GIVE_UP_MS) throw DownloadException(FailReason.Timeout, "The provider did not finish preparing in time")
                            ctx.setQueuedOrPreparing(p.progress, p.reason?.let(FailReason::fromPrepareReason))
                            pause(pollDelayMs(p.retryAfterS))
                        }
                    }
                }
            }
        }
    }

    private suspend fun downloadFile(ctx: JobContext, provider: Provider, file: DownloadFileEntity) {
        if (file.providerIndex < 0) throw DownloadException(FailReason.NotFound, "No provider index for a file")
        val dest = ctx.fileOf(file)
        var failures = 0
        var last: FetchFailure? = null
        while (failures < MAX_LINK_ATTEMPTS) {
            // Never stored, never logged: the link is used immediately.
            when (val link = releases.downloadLink(ctx.releaseHash, file.providerIndex, provider)) {
                is ApiResult.Failure -> throw link.error.toDownloadException()
                is ApiResult.Success -> when (val l = link.value) {
                    is DownloadLink.NotReady -> {
                        val p = l.prepare
                        if (p.state == Prepare.FAILED) throw DownloadException(FailReason.fromPrepareReason(p.reason), "The provider could not prepare this file (${p.reason ?: "failed"})")
                        ctx.setQueuedOrPreparing(p.progress, p.reason?.let(FailReason::fromPrepareReason))
                        pause(pollDelayMs(p.retryAfterS))
                    }
                    is DownloadLink.Redirect -> {
                        ctx.setDownloading()
                        try {
                            val size = fetcher.fetch(l.url, dest, file.expectedSize) { ctx.fileProgress(file, it) }
                            ctx.fileComplete(file, size)
                            return
                        } catch (e: FetchFailure) {
                            when (e.kind) {
                                FetchFailure.Kind.Transient -> throw DownloadException(FailReason.Network, "Connection problem while downloading", retryable = true, cause = e)
                                FetchFailure.Kind.DiskFull -> throw DownloadException(FailReason.NoSpace, "Not enough free space on this phone")
                                else -> {
                                    last = e
                                    failures++
                                }
                            }
                        }
                    }
                }
            }
        }
        throw when (last?.kind) {
            FetchFailure.Kind.SizeMismatch -> DownloadException(FailReason.Integrity, last.message)
            FetchFailure.Kind.BadContent -> DownloadException(FailReason.InvalidContent, last.message)
            else -> DownloadException(FailReason.Network, "The download link was refused", retryable = true)
        }
    }

    companion object {
        const val MAX_LINK_ATTEMPTS = 3
        const val PREPARE_GIVE_UP_MS = 6L * 60 * 60 * 1000
        const val MIN_POLL_S = 2
        const val MAX_POLL_S = 60
        const val DEFAULT_POLL_S = 5

        fun pollDelayMs(retryAfterS: Int?): Long = ((retryAfterS ?: DEFAULT_POLL_S).coerceIn(MIN_POLL_S, MAX_POLL_S)) * 1000L
    }
}

/** Maps an API failure of prepare / the download route to a [DownloadException] (permanent for the user-fixable ones, retryable for transient ones). */
fun ApiError.toDownloadException(): DownloadException = when (this) {
    ApiError.Network -> DownloadException(FailReason.Network, "No connection to the server", retryable = true)
    ApiError.NotSignedIn -> DownloadException(FailReason.Unknown, "Not signed in")
    is ApiError.Unknown -> DownloadException(FailReason.Unknown, message, retryable = true)
    is ApiError.Http -> when {
        code == ErrorCode.NoDebridKey -> DownloadException(FailReason.NoDebridKey, message)
        code == ErrorCode.BadDebridKey -> DownloadException(FailReason.BadDebridKey, message)
        status == 429 || code == ErrorCode.RateLimited -> DownloadException(FailReason.RateLimited, message, retryAfterSec = retryAfterSec)
        status == 404 -> DownloadException(FailReason.NotFound, message)
        code == ErrorCode.UpstreamFailed -> DownloadException(FailReason.DebridFailed, message)
        code == ErrorCode.DebridUnavailable -> DownloadException(FailReason.DebridBusy, message, retryable = true, retryAfterSec = retryAfterSec)
        status in 500..599 -> DownloadException(FailReason.Network, message ?: "Server error $status", retryable = true, retryAfterSec = retryAfterSec)
        else -> DownloadException(FailReason.Unknown, message ?: "HTTP $status")
    }
}
