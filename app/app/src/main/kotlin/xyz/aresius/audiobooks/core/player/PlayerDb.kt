package xyz.aresius.audiobooks.core.player

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Where a user stopped in a book: the local truth the player resumes from (written every few seconds). [dirty] = not yet confirmed by the
 * server, so the row doubles as the queue of writes to send: only the latest state per book is ever kept.
 */
@Entity(tableName = "progress", primaryKeys = ["userId", "asin"])
data class ProgressEntity(
    val userId: String,
    val asin: String,
    val status: String,
    val releaseHash: String?,
    val positionMs: Long,
    val filePath: String?,
    val filePositionMs: Long,
    val speed: Double,
    /** Client clock, ms: the last-write-wins stamp of the contract. */
    val updatedAt: Long,
    val dirty: Boolean,
)

/** The release the user chose for a book (remembered per book). */
@Entity(tableName = "book_pref", primaryKeys = ["userId", "asin"])
data class BookPrefEntity(val userId: String, val asin: String, val releaseHash: String, val provider: String?)

/** A duration learned while playing; shared by every user of the phone (it describes a file, not a person). */
@Entity(tableName = "file_duration", primaryKeys = ["releaseHash", "path"])
data class FileDurationEntity(val releaseHash: String, val path: String, val durationMs: Long)

/**
 * A bookmark of one user. [serverId] is null until the server has it; [deleted] hides a bookmark whose delete still has to reach the
 * server (the row is dropped once it has).
 */
@Entity(tableName = "bookmark", indices = [Index("userId", "asin")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val asin: String,
    val serverId: String?,
    val positionMs: Long,
    val note: String?,
    val createdAtMs: Long,
    val deleted: Boolean = false,
)

@Dao
interface PlayerDao {
    @Query("SELECT * FROM progress WHERE userId = :userId AND asin = :asin")
    suspend fun progress(userId: String, asin: String): ProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putProgress(row: ProgressEntity)

    @Query("SELECT * FROM progress WHERE userId = :userId AND dirty = 1")
    suspend fun dirtyProgress(userId: String): List<ProgressEntity>

    @Query("SELECT COUNT(*) FROM progress WHERE dirty = 1")
    suspend fun dirtyCount(): Int

    /** Clears the dirty flag only if the row was not written again meanwhile. */
    @Query("UPDATE progress SET dirty = 0 WHERE userId = :userId AND asin = :asin AND updatedAt = :updatedAt")
    suspend fun markClean(userId: String, asin: String, updatedAt: Long): Int

    @Query("SELECT * FROM book_pref WHERE userId = :userId AND asin = :asin")
    suspend fun pref(userId: String, asin: String): BookPrefEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPref(pref: BookPrefEntity)

    @Query("SELECT * FROM file_duration WHERE releaseHash = :hash")
    suspend fun durations(hash: String): List<FileDurationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDuration(row: FileDurationEntity)

    @Query("SELECT * FROM bookmark WHERE userId = :userId AND asin = :asin AND deleted = 0 ORDER BY positionMs, id")
    fun observeBookmarks(userId: String, asin: String): kotlinx.coroutines.flow.Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmark WHERE userId = :userId AND asin = :asin")
    suspend fun bookmarks(userId: String, asin: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmark WHERE userId = :userId AND (serverId IS NULL OR deleted = 1)")
    suspend fun unsyncedBookmarks(userId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmark WHERE id = :id")
    suspend fun bookmark(id: Long): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBookmark(row: BookmarkEntity): Long

    @Query("DELETE FROM bookmark WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("DELETE FROM bookmark WHERE userId = :userId AND asin = :asin AND serverId IS NOT NULL AND serverId NOT IN (:keep)")
    suspend fun deleteServerBookmarksNotIn(userId: String, asin: String, keep: List<String>)

    @Query("DELETE FROM progress")
    suspend fun clearProgress()

    @Query("DELETE FROM book_pref")
    suspend fun clearPrefs()

    @Query("DELETE FROM bookmark")
    suspend fun clearBookmarks()
}

@Database(
    entities = [ProgressEntity::class, BookPrefEntity::class, FileDurationEntity::class, BookmarkEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PlayerDb : RoomDatabase() {
    abstract fun dao(): PlayerDao
}
