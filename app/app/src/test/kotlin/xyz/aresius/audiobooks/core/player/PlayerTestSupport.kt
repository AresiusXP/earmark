package xyz.aresius.audiobooks.core.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.LibraryEntry
import xyz.aresius.audiobooks.core.network.LibraryEntryWrite

/** An in-memory [PlayerDao]: same queries as the Room one, so the logic on top of it is tested without Android. */
class FakePlayerDao : PlayerDao {
    val progress = LinkedHashMap<Pair<String, String>, ProgressEntity>()
    val prefs = HashMap<Pair<String, String>, BookPrefEntity>()
    val durations = HashMap<Pair<String, String>, FileDurationEntity>()
    private val bookmarkRows = MutableStateFlow<List<BookmarkEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun progress(userId: String, asin: String) = progress[userId to asin]
    override suspend fun putProgress(row: ProgressEntity) { progress[row.userId to row.asin] = row }
    override suspend fun dirtyProgress(userId: String) = progress.values.filter { it.userId == userId && it.dirty }
    override suspend fun dirtyCount() = progress.values.count { it.dirty }
    override suspend fun markClean(userId: String, asin: String, updatedAt: Long): Int {
        val row = progress[userId to asin] ?: return 0
        if (row.updatedAt != updatedAt) return 0
        progress[userId to asin] = row.copy(dirty = false)
        return 1
    }

    override suspend fun pref(userId: String, asin: String) = prefs[userId to asin]
    override suspend fun putPref(pref: BookPrefEntity) { prefs[pref.userId to pref.asin] = pref }
    override suspend fun durations(hash: String) = durations.values.filter { it.releaseHash == hash }
    override suspend fun putDuration(row: FileDurationEntity) { durations[row.releaseHash to row.path] = row }

    override fun observeBookmarks(userId: String, asin: String): Flow<List<BookmarkEntity>> =
        bookmarkRows.map { rows -> rows.filter { it.userId == userId && it.asin == asin && !it.deleted }.sortedWith(compareBy({ it.positionMs }, { it.id })) }

    override suspend fun bookmarks(userId: String, asin: String) = bookmarkRows.value.filter { it.userId == userId && it.asin == asin }
    override suspend fun unsyncedBookmarks(userId: String) = bookmarkRows.value.filter { it.userId == userId && (it.serverId == null || it.deleted) }
    override suspend fun bookmark(id: Long) = bookmarkRows.value.firstOrNull { it.id == id }
    override suspend fun putBookmark(row: BookmarkEntity): Long {
        val id = if (row.id == 0L) nextId++ else row.id
        bookmarkRows.value = bookmarkRows.value.filter { it.id != id } + row.copy(id = id)
        return id
    }

    override suspend fun deleteBookmark(id: Long) { bookmarkRows.value = bookmarkRows.value.filter { it.id != id } }
    override suspend fun deleteServerBookmarksNotIn(userId: String, asin: String, keep: List<String>) {
        bookmarkRows.value = bookmarkRows.value.filterNot { it.userId == userId && it.asin == asin && it.serverId != null && it.serverId !in keep }
    }

    override suspend fun clearProgress() = progress.clear()
    override suspend fun clearPrefs() = prefs.clear()
    override suspend fun clearBookmarks() { bookmarkRows.value = emptyList() }
}

/** A scripted `/v1/me/library/{asin}`: stores the newest write (last write wins) unless told to fail. */
class FakeProgressRemote : ProgressRemote {
    val entries = HashMap<String, LibraryEntry>()
    val puts = ArrayList<Pair<String, LibraryEntryWrite>>()
    var failure: ApiError? = null
    var getDelayMs = 0L

    override suspend fun get(asin: String): ApiResult<LibraryEntry> {
        if (getDelayMs > 0) kotlinx.coroutines.delay(getDelayMs)
        failure?.let { return ApiResult.Failure(it) }
        return entries[asin]?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.Http(404, xyz.aresius.audiobooks.core.network.ErrorCode.NotFound))
    }

    override suspend fun put(asin: String, write: LibraryEntryWrite): ApiResult<LibraryEntry> {
        failure?.let { return ApiResult.Failure(it) }
        puts.add(asin to write)
        val old = entries[asin]
        val entry = if (old != null && old.updatedAt > write.updatedAt) old
        else LibraryEntry(testBook(asin), write.status, write.releaseHash, write.positionMs ?: 0, write.filePath, write.filePositionMs ?: 0, write.speed ?: 1.0, write.updatedAt)
        entries[asin] = entry
        return ApiResult.Success(entry)
    }
}

fun testBook(asin: String = "B001", runtimeMin: Int = 60) = Book(asin = asin, title = "Title $asin", authors = listOf("An Author"), runtimeMin = runtimeMin)

fun point(
    status: String = "listening", hash: String? = "h", position: Long = 1000, path: String? = "a.mp3", filePosition: Long = 1000, speed: Double = 1.0, updatedAt: Long = 0,
) = ResumePoint(status, hash, position, path, filePosition, speed, updatedAt)

class Counter : SyncScheduler {
    var scheduled = 0
    override fun schedule() { scheduled++ }
}
