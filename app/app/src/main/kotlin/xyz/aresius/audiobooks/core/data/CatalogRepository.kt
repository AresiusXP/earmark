package xyz.aresius.audiobooks.core.data

import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Singleton
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.network.ApiProvider
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.AudiobooksApi
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.BookDetail
import xyz.aresius.audiobooks.core.network.Chapters
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.HomeResponse
import xyz.aresius.audiobooks.core.network.HomeShelf
import xyz.aresius.audiobooks.core.network.HomeShelfDto
import xyz.aresius.audiobooks.core.network.HomeShelfKind
import xyz.aresius.audiobooks.core.network.toApiError
import xyz.aresius.audiobooks.core.network.safeApiCall

/**
 * Search, the home shelves and book pages, cache-through: a fresh cached answer is served without a request unless [refresh] is set; a failed
 * request serves the cached answer, even an old one, marked stale (offline use). Fresh means the server's own `max-age` (5 minutes) for
 * search and discover; a BOOK page is `private, no-store` on the server because it carries per-user fields (`cached`, `usable`), so it is
 * only reused for [BOOK_FRESH_MS] (a screen and the player opening the same book together). There is no OkHttp cache in the app.
 */
interface CatalogRepository {
    suspend fun home(refresh: Boolean = false): ApiResult<List<HomeShelf>>
    suspend fun search(query: String, refresh: Boolean = false): ApiResult<List<Book>>
    suspend fun book(asin: String, refresh: Boolean = false): ApiResult<BookDetail>
    suspend fun chapters(asin: String): ApiResult<Chapters>
}

@Singleton
class CatalogRepositoryImpl(
    private val apiSource: suspend () -> AudiobooksApi,
    private val cache: CacheDao,
    private val clock: () -> Long,
    /** The signed-in user: the home shelves depend on their library, so their cache key carries it. */
    private val userId: () -> String? = { null },
) : CatalogRepository {
    @Inject
    constructor(provider: ApiProvider, cache: CacheDao, sessions: SessionStore) :
        this({ provider.api() }, cache, System::currentTimeMillis, { sessions.snapshot()?.userId })

    /**
     * The named shelves of `/v1/home`. A server without that route (or failing on it) falls back to the first discover page as one
     * "bestsellers" shelf; a network failure or a signed-out session does not, it serves the saved copy like everything else.
     */
    override suspend fun home(refresh: Boolean): ApiResult<List<HomeShelf>> =
        when (val r = cached("home:${userId().orEmpty()}", ListSerializer(HomeShelfDto.serializer()), refresh) { fetchHome() }) {
            is ApiResult.Success -> ApiResult.Success(r.value.mapNotNull { it.toShelf() }, r.stale)
            is ApiResult.Failure -> r
        }

    private suspend fun fetchHome(): List<HomeShelfDto> = try {
        apiSource().home().shelves
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        if (e.toApiError() !is ApiError.Http) throw e
        listOf(HomeShelfDto("bestsellers", HomeShelfKind.Bestsellers.wire, null, apiSource().discover(0).books))
    }

    private fun HomeShelfDto.toShelf(): HomeShelf? {
        val k = HomeShelfKind.fromWire(kind)
        return if (k == HomeShelfKind.Unknown || books.isEmpty()) null else HomeShelf(id, k, arg, books)
    }

    override suspend fun search(query: String, refresh: Boolean): ApiResult<List<Book>> =
        cached("search:${query.trim().lowercase()}", ListSerializer(Book.serializer()), refresh) { apiSource().search(query.trim()).books }

    override suspend fun book(asin: String, refresh: Boolean): ApiResult<BookDetail> =
        cached("book:$asin", BookDetail.serializer(), refresh, BOOK_FRESH_MS) { apiSource().getBook(asin) }

    override suspend fun chapters(asin: String): ApiResult<Chapters> = safeApiCall { apiSource().getChapters(asin) }

    private suspend fun <T> cached(key: String, serializer: KSerializer<T>, refresh: Boolean, freshMs: Long = FRESH_MS, fetch: suspend () -> T): ApiResult<T> {
        val entry = cache.get(key)
        val cachedValue = entry?.let { runCatching { AudiobooksJson.decodeFromString(serializer, it.json) }.getOrNull() }
        if (!refresh && entry != null && cachedValue != null && clock() - entry.fetchedAtMs in 0 until freshMs) {
            return ApiResult.Success(cachedValue)
        }
        return when (val r = safeApiCall { fetch() }) {
            is ApiResult.Success -> {
                cache.put(CacheEntryEntity(key, AudiobooksJson.encodeToString(serializer, r.value), clock()))
                r
            }
            is ApiResult.Failure -> if (cachedValue != null) ApiResult.Success(cachedValue, stale = true) else r
        }
    }

    companion object {
        const val FRESH_MS = 5L * 60 * 1000
        const val BOOK_FRESH_MS = 5_000L
    }
}
