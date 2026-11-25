package xyz.aresius.audiobooks.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/** A cached JSON answer (discover page, search, book page, the signed-in user). Pure cache: safe to drop. */
@Entity(tableName = "cache_entry")
data class CacheEntryEntity(
    @PrimaryKey val key: String,
    val json: String,
    val fetchedAtMs: Long,
)

/** One library entry of one user; the book is kept as JSON so the list renders offline without extra joins. */
@Entity(tableName = "library_entry", primaryKeys = ["userId", "asin"])
data class LibraryEntryEntity(
    val userId: String,
    val asin: String,
    val bookJson: String,
    val status: String,
    val releaseHash: String?,
    val positionMs: Long,
    val filePath: String?,
    val filePositionMs: Long,
    val speed: Double,
    val updatedAt: Long,
    /** Server time (ms) the status became `finished` (contract v1.1); null when unknown (not finished, or an older server's answer). */
    val finishedAtMs: Long? = null,
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache_entry WHERE `key` = :key")
    suspend fun get(key: String): CacheEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: CacheEntryEntity)

    @Query("DELETE FROM cache_entry")
    suspend fun clear()
}

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM library_entry WHERE userId = :userId ORDER BY updatedAt DESC")
    abstract fun observeAll(userId: String): Flow<List<LibraryEntryEntity>>

    @Query("SELECT * FROM library_entry WHERE userId = :userId AND asin = :asin")
    abstract suspend fun get(userId: String, asin: String): LibraryEntryEntity?

    @Query("SELECT * FROM library_entry WHERE userId = :userId AND asin = :asin")
    abstract fun observe(userId: String, asin: String): Flow<LibraryEntryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entry: LibraryEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(entries: List<LibraryEntryEntity>)

    @Query("DELETE FROM library_entry WHERE userId = :userId AND asin = :asin")
    abstract suspend fun delete(userId: String, asin: String)

    @Query("DELETE FROM library_entry WHERE userId = :userId")
    abstract suspend fun deleteAll(userId: String)

    @Query("DELETE FROM library_entry")
    abstract suspend fun clear()

    /** The server's list is the truth: replaces everything of this user in one transaction. */
    @Transaction
    open suspend fun replaceAll(userId: String, entries: List<LibraryEntryEntity>) {
        deleteAll(userId)
        upsertAll(entries)
    }
}

@Database(entities = [CacheEntryEntity::class, LibraryEntryEntity::class], version = 2, exportSchema = true)
abstract class AudiobooksDb : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
    abstract fun libraryDao(): LibraryDao

    companion object {
        /** v2 (contract v1.1): `library_entry.finishedAtMs`. Kept as a real migration so the offline caches (book pages, chapters) survive. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `library_entry` ADD COLUMN `finishedAtMs` INTEGER")
            }
        }
    }
}
