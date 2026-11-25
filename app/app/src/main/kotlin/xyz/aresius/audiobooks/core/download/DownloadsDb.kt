package xyz.aresius.audiobooks.core.download

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

object JobState {
    const val QUEUED = "queued"
    const val PREPARING = "preparing"
    const val DOWNLOADING = "downloading"

    /** Held back by a policy of the torrent engine (see [WaitReason]); [DownloadBookEntity.pauseReason] says which. */
    const val PAUSED = "paused"
    /** Paused by the user: partial files stay, no work is scheduled, nothing restarts it but Resume. Not in [ACTIVE] on purpose (reconcile never re-enqueues it). */
    const val USER_PAUSED = "user_paused"
    const val FAILED = "failed"
    const val COMPLETED = "completed"
    val ACTIVE = listOf(QUEUED, PREPARING, DOWNLOADING, PAUSED)
}

object FileState {
    const val PENDING = "pending"
    const val COMPLETE = "complete"
}

/** One book being downloaded or downloaded, of one user. At most one release per book. */
@Entity(tableName = "download_book", primaryKeys = ["userId", "asin"])
data class DownloadBookEntity(
    val userId: String,
    val asin: String,
    val releaseHash: String,
    /** A provider wire name ("torbox", "realdebrid") or "torrent". */
    val source: String,
    val magnet: String,
    val bookJson: String,
    /** Size of the whole release as the server listed it: a hint for the free-space check when file sizes are unknown. */
    val sizeHint: Long,
    /** True when the release is a multi-book pack (its file list is then required). */
    val collection: Boolean,
    val state: String,
    val failReason: String?,
    val failMessage: String?,
    val retryAfterSec: Int?,
    val pauseReason: String?,
    val preparePercent: Int?,
    val slowReason: String?,
    /** Never use mobile data for this book whatever the setting says (auto-download). */
    val unmeteredOnly: Boolean,
    /** Failures in a row without progress; reset when bytes arrive. */
    val attempts: Int,
    val preparingSinceMs: Long?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val completedAtMs: Long?,
)

/** One audio file of a book. [path] is the server's path (what the player asks for); [localPath] is the sanitized path below the book directory. */
@Entity(tableName = "download_file", primaryKeys = ["userId", "asin", "releaseHash", "path"])
data class DownloadFileEntity(
    val userId: String,
    val asin: String,
    val releaseHash: String,
    val path: String,
    val localPath: String,
    val ordinal: Int,
    /** The provider's file index for the download route, -1 until known (torrents have none). */
    val providerIndex: Int,
    /** Size the server reported (0 = unknown). */
    val expectedSize: Long,
    /** Bytes on disk so far (a `.part` file or, for torrents, the engine's file). */
    val bytesDone: Long,
    /** Final size of a complete file (0 until then). */
    val size: Long,
    val state: String,
)

@Dao
abstract class DownloadsDao {
    @Query("SELECT * FROM download_book WHERE userId = :userId ORDER BY createdAtMs DESC")
    abstract fun observeBooks(userId: String): Flow<List<DownloadBookEntity>>

    @Query("SELECT * FROM download_book WHERE userId = :userId AND asin = :asin")
    abstract fun observeBook(userId: String, asin: String): Flow<DownloadBookEntity?>

    @Query("SELECT * FROM download_book WHERE userId = :userId AND asin = :asin")
    abstract suspend fun book(userId: String, asin: String): DownloadBookEntity?

    @Query("SELECT * FROM download_book WHERE userId = :userId")
    abstract suspend fun books(userId: String): List<DownloadBookEntity>

    @Query("SELECT * FROM download_book")
    abstract suspend fun allBooks(): List<DownloadBookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertBook(book: DownloadBookEntity)

    @Query("DELETE FROM download_book WHERE userId = :userId AND asin = :asin")
    abstract suspend fun deleteBook(userId: String, asin: String)

    @Query("SELECT * FROM download_file WHERE userId = :userId ORDER BY asin, ordinal")
    abstract fun observeFiles(userId: String): Flow<List<DownloadFileEntity>>

    @Query("SELECT * FROM download_file WHERE userId = :userId AND asin = :asin ORDER BY ordinal")
    abstract fun observeBookFiles(userId: String, asin: String): Flow<List<DownloadFileEntity>>

    @Query("SELECT * FROM download_file WHERE userId = :userId AND state = 'complete'")
    abstract fun observeCompleteFiles(userId: String): Flow<List<DownloadFileEntity>>

    @Query("SELECT * FROM download_file WHERE userId = :userId AND asin = :asin ORDER BY ordinal")
    abstract suspend fun files(userId: String, asin: String): List<DownloadFileEntity>

    @Query("SELECT * FROM download_file")
    abstract suspend fun allFiles(): List<DownloadFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertFiles(files: List<DownloadFileEntity>)

    @Query("DELETE FROM download_file WHERE userId = :userId AND asin = :asin")
    abstract suspend fun deleteFiles(userId: String, asin: String)

    @Query("UPDATE download_file SET bytesDone = :bytes WHERE userId = :userId AND asin = :asin AND releaseHash = :hash AND path = :path AND state = 'pending'")
    abstract suspend fun setBytes(userId: String, asin: String, hash: String, path: String, bytes: Long)

    @Query("UPDATE download_file SET state = 'complete', size = :size, bytesDone = :size WHERE userId = :userId AND asin = :asin AND releaseHash = :hash AND path = :path")
    abstract suspend fun markComplete(userId: String, asin: String, hash: String, path: String, size: Long)

    @Query("UPDATE download_file SET state = 'pending', size = 0, bytesDone = 0 WHERE userId = :userId AND asin = :asin AND releaseHash = :hash AND path = :path")
    abstract suspend fun markPending(userId: String, asin: String, hash: String, path: String)

    @Query("DELETE FROM download_book")
    abstract suspend fun clearBooks()

    @Query("DELETE FROM download_file")
    abstract suspend fun clearFiles()

    @Transaction
    open suspend fun replaceBook(book: DownloadBookEntity, files: List<DownloadFileEntity>) {
        deleteFiles(book.userId, book.asin)
        upsertBook(book)
        if (files.isNotEmpty()) upsertFiles(files)
    }

    /** The file list of a book replaced in one step, so observers never see it empty in between. */
    @Transaction
    open suspend fun replaceFiles(userId: String, asin: String, files: List<DownloadFileEntity>) {
        deleteFiles(userId, asin)
        if (files.isNotEmpty()) upsertFiles(files)
    }

    @Transaction
    open suspend fun removeBook(userId: String, asin: String) {
        deleteFiles(userId, asin)
        deleteBook(userId, asin)
    }

    /** Removes the book only if its job is still [JobState.COMPLETED] right now (not re-queued or downloading again); returns the removed row, else null. */
    @Transaction
    open suspend fun removeBookIfCompleted(userId: String, asin: String): DownloadBookEntity? {
        val current = book(userId, asin)?.takeIf { it.state == JobState.COMPLETED } ?: return null
        deleteFiles(userId, asin)
        deleteBook(userId, asin)
        return current
    }

    @Transaction
    open suspend fun clearAll() {
        clearFiles()
        clearBooks()
    }

    /** Read-modify-write of the job row; false when the row is gone (the book was deleted meanwhile). */
    @Transaction
    open suspend fun updateBook(userId: String, asin: String, change: (DownloadBookEntity) -> DownloadBookEntity): Boolean {
        val current = book(userId, asin) ?: return false
        upsertBook(change(current))
        return true
    }
}

/** Own database (not [xyz.aresius.audiobooks.core.data.AudiobooksDb]): downloads must survive a rebuild of the cache and own their schema. */
@Database(entities = [DownloadBookEntity::class, DownloadFileEntity::class], version = 1, exportSchema = true)
abstract class DownloadsDb : RoomDatabase() {
    abstract fun downloadsDao(): DownloadsDao
}
