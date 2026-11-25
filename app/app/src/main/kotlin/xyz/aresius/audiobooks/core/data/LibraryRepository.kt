package xyz.aresius.audiobooks.core.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiProvider
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.AudiobooksApi
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.LibraryEntry
import xyz.aresius.audiobooks.core.network.LibraryEntryWrite
import xyz.aresius.audiobooks.core.network.LibraryStatus
import xyz.aresius.audiobooks.core.network.map
import xyz.aresius.audiobooks.core.network.safeApiCall

/**
 * The caller's library: the server is the truth, Room is the copy the UI observes (so the list opens offline and
 * after a failed refresh). Every row is scoped by the signed-in user's id.
 */
@Singleton
class LibraryRepository(
    private val apiSource: suspend () -> AudiobooksApi,
    private val dao: LibraryDao,
    private val sessions: SessionStore,
    private val clock: () -> Long,
) {
    @Inject
    constructor(provider: ApiProvider, dao: LibraryDao, sessions: SessionStore) :
        this({ provider.api() }, dao, sessions, System::currentTimeMillis)

    @OptIn(ExperimentalCoroutinesApi::class)
    val entries: Flow<List<LibraryEntry>> = sessions.session.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else dao.observeAll(s.userId).map { rows -> rows.map { it.toEntry() } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun entry(asin: String): Flow<LibraryEntry?> = sessions.session.flatMapLatest { s ->
        if (s == null) flowOf(null) else dao.observe(s.userId, asin).map { it?.toEntry() }
    }

    /** Replaces the cached list with the server's. A failure leaves the cache untouched. */
    suspend fun refresh(): ApiResult<Unit> {
        val userId = sessions.load()?.userId ?: return ApiResult.Failure(ApiError.NotSignedIn)
        return when (val r = safeApiCall { apiSource().listLibrary().entries }) {
            is ApiResult.Success -> {
                dao.replaceAll(userId, r.value.map { it.toEntity(userId) })
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> r
        }
    }

    /** Adds [book] as "saved". The caller only offers this for a book that is not in the library yet (a PUT would overwrite its progress). */
    suspend fun add(book: Book): ApiResult<Unit> {
        val userId = sessions.load()?.userId ?: return ApiResult.Failure(ApiError.NotSignedIn)
        return when (val r = safeApiCall { apiSource().putLibraryEntry(book.asin, LibraryEntryWrite(status = LibraryStatus.SAVED, updatedAt = clock())) }) {
            is ApiResult.Success -> {
                // The server answers with the stored entry (its own book data wins over what the list screen had).
                dao.upsert(r.value.toEntity(userId))
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> r
        }
    }

    suspend fun remove(asin: String): ApiResult<Unit> {
        val userId = sessions.load()?.userId ?: return ApiResult.Failure(ApiError.NotSignedIn)
        return safeApiCall { apiSource().deleteLibraryEntry(asin) }.map { }.also { if (it is ApiResult.Success) dao.delete(userId, asin) }
    }

    suspend fun clear() = dao.clear()
}

internal fun LibraryEntry.toEntity(userId: String) = LibraryEntryEntity(
    userId = userId,
    asin = book.asin,
    bookJson = AudiobooksJson.encodeToString(Book.serializer(), book),
    status = status,
    releaseHash = releaseHash,
    positionMs = positionMs,
    filePath = filePath,
    filePositionMs = filePositionMs,
    speed = speed,
    updatedAt = updatedAt,
)

internal fun LibraryEntryEntity.toEntry() = LibraryEntry(
    book = AudiobooksJson.decodeFromString(Book.serializer(), bookJson),
    status = status,
    releaseHash = releaseHash,
    positionMs = positionMs,
    filePath = filePath,
    filePositionMs = filePositionMs,
    speed = speed,
    updatedAt = updatedAt,
)
