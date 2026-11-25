package xyz.aresius.audiobooks.core.player

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.network.ApiProvider
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.AudiobooksApi
import xyz.aresius.audiobooks.core.network.CreateBookmarkRequest
import xyz.aresius.audiobooks.core.network.safeApiCall

/** A bookmark as the player shows it: [id] is the local row id (stable even before the server knows it). */
data class PlayerBookmark(val id: Long, val positionMs: Long, val note: String?, val createdAtMs: Long, val synced: Boolean)

/**
 * Bookmarks of the current user: stored locally first (so they work offline and appear at once), mirrored to
 * `/v1/me/library/{asin}/bookmarks` by [sync] (creates and deletes that could not be sent yet are retried there; the server's list is
 * merged in). The book has to be in the library on the server before it accepts a bookmark: playing adds it, so a 404 simply waits.
 */
@Singleton
class BookmarkRepository(
    private val dao: PlayerDao,
    private val apiSource: suspend () -> AudiobooksApi,
    private val sessions: SessionStore,
    private val clock: () -> Long,
) {
    @Inject
    constructor(dao: PlayerDao, provider: ApiProvider, sessions: SessionStore) :
        this(dao, { provider.api() }, sessions, System::currentTimeMillis)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(asin: String): Flow<List<PlayerBookmark>> = sessions.session.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else dao.observeBookmarks(s.userId, asin).map { rows -> rows.map { it.toModel() } }
    }

    suspend fun add(asin: String, positionMs: Long, note: String?): PlayerBookmark? {
        val userId = sessions.load()?.userId ?: return null
        val row = BookmarkEntity(userId = userId, asin = asin, serverId = null, positionMs = positionMs.coerceAtLeast(0), note = note?.trim()?.take(MAX_NOTE)?.ifBlank { null }, createdAtMs = clock())
        val id = dao.putBookmark(row)
        pushOne(row.copy(id = id))
        return dao.bookmark(id)?.toModel()
    }

    suspend fun delete(id: Long) {
        val row = dao.bookmark(id) ?: return
        if (row.serverId == null) {
            dao.deleteBookmark(id)
        } else {
            dao.putBookmark(row.copy(deleted = true))
            pushOne(row.copy(deleted = true))
        }
    }

    /** Sends what is pending for the user, then merges the server's list of [asin] (null = only send). Failures leave everything as it was. */
    suspend fun sync(asin: String?) {
        val userId = sessions.load()?.userId ?: return
        for (row in dao.unsyncedBookmarks(userId)) pushOne(row)
        if (asin == null) return
        val r = safeApiCall { apiSource().listBookmarks(asin) }
        if (r !is ApiResult.Success) return
        val known = dao.bookmarks(userId, asin).mapNotNull { it.serverId }.toSet()
        for (b in r.value.bookmarks) {
            if (b.id in known) continue
            dao.putBookmark(BookmarkEntity(userId = userId, asin = asin, serverId = b.id, positionMs = b.positionMs, note = b.note?.ifBlank { null }, createdAtMs = parseInstant(b.createdAt)))
        }
        dao.deleteServerBookmarksNotIn(userId, asin, r.value.bookmarks.map { it.id })
    }

    private suspend fun pushOne(row: BookmarkEntity) {
        when {
            row.deleted -> {
                val serverId = row.serverId ?: return dao.deleteBookmark(row.id)
                if (safeApiCall { apiSource().deleteBookmark(row.asin, serverId) } is ApiResult.Success) dao.deleteBookmark(row.id)
            }
            row.serverId == null -> {
                val r = safeApiCall { apiSource().createBookmark(row.asin, CreateBookmarkRequest(row.positionMs, row.note)) }
                if (r is ApiResult.Success) dao.bookmark(row.id)?.let { dao.putBookmark(it.copy(serverId = r.value.id)) }
            }
        }
    }

    private fun parseInstant(iso: String): Long = runCatching { Instant.parse(iso).toEpochMilli() }.getOrElse { clock() }

    private fun BookmarkEntity.toModel() = PlayerBookmark(id, positionMs, note, createdAtMs, synced = serverId != null)

    companion object {
        const val MAX_NOTE = 280
    }
}
