package xyz.aresius.audiobooks.core.player

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.data.CacheDao
import xyz.aresius.audiobooks.core.data.CacheEntryEntity
import xyz.aresius.audiobooks.core.data.CatalogRepository
import xyz.aresius.audiobooks.core.data.MeRepository
import xyz.aresius.audiobooks.core.download.LocalFiles
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.Chapters
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.ReleaseView
import xyz.aresius.audiobooks.core.network.TorrentFile
import xyz.aresius.audiobooks.core.network.isNotFound

/**
 * One file to play: [local] (a complete copy on the phone) wins over streaming it by [index] from [PlayPlan.provider]. [durationMs] is what is
 * known before the file is prepared: the length this phone learned, else the one the server has from other phones ([serverDurationMs]).
 */
data class PlayFile(
    val path: String,
    val index: Int?,
    val sizeBytes: Long,
    val durationMs: Long?,
    val local: File?,
    val serverDurationMs: Long? = null,
) {
    fun playable(provider: Provider?): Boolean = local != null || (provider != null && index != null)
}

/** Everything needed to start a book, before any audio is touched. */
data class PlayPlan(
    val userId: String,
    val book: Book,
    val release: ReleaseView,
    val provider: Provider?,
    val files: List<PlayFile>,
    val chapters: Chapters?,
    val resume: ResumePoint?,
) {
    /** True when a file has to come from the provider (so the release must be prepared there first). */
    val needsStream: Boolean get() = files.any { it.local == null }
}

sealed interface LoadResult {
    data class Ready(val plan: PlayPlan) : LoadResult
    data class Failure(val kind: FailureKind, val message: String? = null) : LoadResult
}

/**
 * Gathers what the player needs for a book: the catalog entry (cache-through, so it works offline), the release to play (remembered choice,
 * else the best one the user can stream, see [ReleaseChooser]), which files are on the phone, learned file durations, the chapters and
 * where to resume (newer of the local and the server's entry).
 */
@Singleton
class BookLoader(
    private val catalog: CatalogRepository,
    private val localFiles: LocalFiles,
    private val sync: ProgressSync,
    private val dao: PlayerDao,
    private val cache: CacheDao,
    private val sessions: SessionStore,
    private val preferredProvider: suspend () -> Provider?,
    private val clock: () -> Long,
) {
    @Inject
    constructor(
        catalog: CatalogRepository,
        localFiles: LocalFiles,
        sync: ProgressSync,
        dao: PlayerDao,
        cache: CacheDao,
        sessions: SessionStore,
        me: MeRepository,
    ) : this(catalog, localFiles, sync, dao, cache, sessions, {
        me.restore()
        Provider.fromWire(me.me.value?.settings?.preferredProvider)
    }, System::currentTimeMillis)

    /** The book page carries per-user fields (`usable`); [CatalogRepository.book] only reuses an answer that is a few seconds old. */
    suspend fun load(asin: String, requestedHash: String?): LoadResult {
        val userId = sessions.load()?.userId ?: return LoadResult.Failure(FailureKind.NotSignedIn)
        val detail = when (val r = catalog.book(asin)) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> return LoadResult.Failure(if (r.error.isNotFound) FailureKind.NoReleases else FailureKind.Network)
        }
        if (detail.releases.isEmpty()) return LoadResult.Failure(FailureKind.NoReleases)
        val resume = sync.resolve(userId, asin)
        val remembered = requestedHash?.takeIf { h -> detail.releases.any { it.infoHash == h } }
            ?: dao.pref(userId, asin)?.releaseHash
            ?: resume?.releaseHash
        val withLocal = detail.releases.filter { r -> filesOf(asin, r).any { it.local != null } }.mapTo(HashSet()) { it.infoHash }
        val choice = ReleaseChooser.choose(detail.releases, remembered, preferredProvider(), hasLocal = { it.infoHash in withLocal })
            ?: return LoadResult.Failure(FailureKind.NoReleases)
        val release = choice.release
        val files = filesOf(asin, release)
        if (files.isEmpty() && choice.provider == null) return LoadResult.Failure(FailureKind.NeedsSource)
        if (files.isNotEmpty() && files.any { !it.playable(choice.provider) }) return LoadResult.Failure(FailureKind.NeedsSource)
        val chapters = chapters(asin)
        return LoadResult.Ready(PlayPlan(userId, detail.book, release, choice.provider, files, chapters, resume))
    }

    /**
     * The files of [release] with what is known about each; empty when the server has no list yet (the provider's `prepare` gives one).
     * Initial durations, by PATH: what this phone learned by preparing the file, else `duration_ms` of the book page (`files`, then
     * `torrent_files`: other phones reported it), else null (the timeline estimates it from the catalog runtime).
     */
    suspend fun filesOf(asin: String, release: ReleaseView): List<PlayFile> {
        val listed = release.files.ifEmpty { release.torrentFiles }
        val debridIndex = release.files.associate { it.path to it.index }
        return playFiles(asin, release, listed) { f -> debridIndex[f.path] }
    }

    /** The plan's files once `prepare` has delivered the provider's list (the server had none). */
    suspend fun withProviderFiles(plan: PlayPlan, files: List<TorrentFile>): PlayPlan =
        plan.copy(files = playFiles(plan.book.asin, plan.release, files) { f -> f.index })

    private suspend fun playFiles(asin: String, release: ReleaseView, listed: List<TorrentFile>, index: (TorrentFile) -> Int?): List<PlayFile> {
        val learned = dao.durations(release.infoHash).associate { it.path to it.durationMs }
        val server = serverDurations(release)
        return listed.map { f ->
            val fromServer = server[f.path] ?: f.durationMs
            PlayFile(f.path, index(f), f.size, learned[f.path] ?: fromServer, localFiles.localFile(asin, release.infoHash, f.path), fromServer)
        }
    }

    private fun serverDurations(release: ReleaseView): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (f in release.files + release.torrentFiles) f.durationMs?.takeIf { it > 0 }?.let { out.putIfAbsent(f.path, it) }
        return out
    }

    /** Remembers the release the user plays for this book. */
    suspend fun remember(userId: String, asin: String, release: ReleaseView, provider: Provider?) {
        dao.putPref(BookPrefEntity(userId, asin, release.infoHash, provider?.wire))
    }

    /**
     * Stores a duration ExoPlayer measured by preparing the file. [serverKnows]: the server already has a duration for the path (first
     * writer wins there), so there is nothing to report. A row that already holds this value keeps its `reported` flag.
     */
    suspend fun learnDuration(releaseHash: String, path: String, durationMs: Long, serverKnows: Boolean = false) {
        val old = dao.durations(releaseHash).firstOrNull { it.path == path }
        if (old != null && old.durationMs == durationMs) return
        dao.putDuration(FileDurationEntity(releaseHash, path, durationMs, reported = serverKnows))
    }

    private suspend fun chapters(asin: String): Chapters? {
        val key = "chapters:$asin"
        val fresh = withTimeoutOrNull(CHAPTERS_TIMEOUT_MS) { catalog.chapters(asin) }
        if (fresh is ApiResult.Success) {
            cache.put(CacheEntryEntity(key, AudiobooksJson.encodeToString(Chapters.serializer(), fresh.value), clock()))
            return fresh.value
        }
        return cache.get(key)?.let { runCatching { AudiobooksJson.decodeFromString(Chapters.serializer(), it.json) }.getOrNull() }
    }

    private companion object {
        const val CHAPTERS_TIMEOUT_MS = 6_000L
    }
}
