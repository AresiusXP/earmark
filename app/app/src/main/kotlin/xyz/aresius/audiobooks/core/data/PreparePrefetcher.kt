package xyz.aresius.audiobooks.core.data

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.ReleaseView

/** Warms a release before the user presses Play (the Book page calls it for the release the player would choose). */
fun interface ReleasePrefetch {
    fun prefetch(release: ReleaseView, provider: Provider): Boolean
}

/**
 * Opening a book page starts the provider's `prepare` for the release Play would stream, so the server (which remembers a ready release for a
 * while) and the debrid account are warm when the player asks: Play then skips the several seconds `prepare` takes on a cold release.
 *
 * Only for a debrid provider the user can already use (the caller passes the provider the player would choose, so never the BitTorrent path),
 * only for a release that is cached there (an uncached one would start a real add and spend the key's add budget just by looking at a page),
 * and at most once per release per [ttlMs]: the answer is ignored, a failure is not retried inside that window and never shown.
 */
@Singleton
class PreparePrefetcher(
    private val releases: ReleaseRepository,
    private val sessions: SessionStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val ttlMs: Long,
) : ReleasePrefetch {
    @Inject
    constructor(releases: ReleaseRepository, sessions: SessionStore) :
        this(releases, sessions, CoroutineScope(SupervisorJob() + Dispatchers.IO), System::currentTimeMillis, TTL_MS)

    private val started = ConcurrentHashMap<String, Long>()

    /** True when a prepare was started now; false when this release was asked recently or the release is not worth warming. */
    override fun prefetch(release: ReleaseView, provider: Provider): Boolean {
        if (release.cached[provider.wire] != true || provider.wire !in release.usable.debrid) return false
        val user = sessions.snapshot()?.userId ?: return false
        val key = "$user/${provider.wire}/${release.infoHash}"
        val now = clock()
        var start = false
        started.compute(key) { _, last ->
            if (last != null && now - last < ttlMs) last else now.also { start = true }
        }
        if (!start) return false
        if (started.size > MAX_KEYS) started.entries.removeIf { now - it.value >= ttlMs }
        scope.launch { releases.prepare(release.infoHash, provider, release.magnet.ifBlank { null }) }
        return true
    }

    companion object {
        const val TTL_MS = 5L * 60 * 1000
        private const val MAX_KEYS = 200
    }
}
