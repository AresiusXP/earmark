package xyz.aresius.audiobooks.core.player

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.AudiobooksApi
import xyz.aresius.audiobooks.core.network.DurationEntry
import xyz.aresius.audiobooks.core.network.DurationsRequest
import xyz.aresius.audiobooks.core.network.safeApiCall

/**
 * Tells the server the real length of audio files this phone learned by preparing them in the player (`POST /v1/releases/{hash}/durations`),
 * so the next listener gets exact chapters and a seek bar from the start. Best effort and never in the way of playback:
 *
 * * only [FileDurationEntity] rows with `reported = false` are sent: lengths ExoPlayer measured here (a length the book page already carried
 *   is stored as reported, and the server never replaces a stored length anyway: first writer wins);
 * * one request per release carries everything pending for it (at most [MAX_ENTRIES]), and each file goes out once: the row is marked
 *   `reported` when the server answered 200 (accepted or ignored: nothing to retry) or refused it for good (400, 404);
 * * anything else (offline, 429, 5xx, 401) keeps the rows and asks [scheduler] for a retry when there is a network (the progress-sync worker).
 */
class DurationReporter(
    private val dao: PlayerDao,
    private val apiSource: suspend () -> AudiobooksApi,
    private val scheduler: SyncScheduler,
) {
    private val lock = Mutex()

    /** Sends what is pending. Returns true when nothing is left that a retry could still send. */
    suspend fun flush(): Boolean = lock.withLock {
        var done = true
        val pending = dao.unreportedDurations()
        for ((hash, rows) in pending.groupBy { it.releaseHash }) {
            val (valid, hopeless) = rows.partition { it.durationMs in MIN_MS..MAX_MS }
            hopeless.forEach { dao.markReported(it.releaseHash, it.path, it.durationMs) }
            for (chunk in valid.chunked(MAX_ENTRIES)) {
                val request = DurationsRequest(chunk.map { DurationEntry(it.path, it.durationMs) })
                when (val r = safeApiCall { apiSource().reportDurations(hash, request) }) {
                    is ApiResult.Success -> chunk.forEach { dao.markReported(it.releaseHash, it.path, it.durationMs) }
                    is ApiResult.Failure -> when {
                        r.error == ApiError.NotSignedIn -> return@withLock true
                        r.error.isPermanent() -> chunk.forEach { dao.markReported(it.releaseHash, it.path, it.durationMs) }
                        else -> done = false
                    }
                }
            }
        }
        if (!done) scheduler.schedule()
        done
    }

    private fun ApiError.isPermanent() = this is ApiError.Http && status in setOf(400, 404)

    companion object {
        /** The contract's bounds: 1 s .. 48 h per file, at most 2000 entries per request. */
        const val MIN_MS = 1_000L
        const val MAX_MS = 172_800_000L
        const val MAX_ENTRIES = 2_000
    }
}
