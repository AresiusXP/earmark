package xyz.aresius.audiobooks.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.aresius.audiobooks.core.player.PlayerDb

/**
 * The v1 -> v2 migrations (contract v1.1) on a database that really has v1 data: the file is made with the v1 SQL of the exported v1 schemas (app/schemas),
 * then opened through Room, which runs the migration and validates the result against the v2 schema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RoomMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun v1(name: String, vararg sql: String) {
        context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).also { it.parentFile?.mkdirs() }, null)
        sql.forEach { db.execSQL(it) }
        db.version = 1
        db.close()
    }

    @Test fun playerDbKeepsProgressBookmarksAndLearnedDurationsAndAddsTheNewColumns() = runBlocking<Unit> {
        v1(
            "player-v1.db",
            "CREATE TABLE `progress` (`userId` TEXT NOT NULL, `asin` TEXT NOT NULL, `status` TEXT NOT NULL, `releaseHash` TEXT, `positionMs` INTEGER NOT NULL, `filePath` TEXT, `filePositionMs` INTEGER NOT NULL, `speed` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`userId`, `asin`))",
            "CREATE TABLE `book_pref` (`userId` TEXT NOT NULL, `asin` TEXT NOT NULL, `releaseHash` TEXT NOT NULL, `provider` TEXT, PRIMARY KEY(`userId`, `asin`))",
            "CREATE TABLE `file_duration` (`releaseHash` TEXT NOT NULL, `path` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, PRIMARY KEY(`releaseHash`, `path`))",
            "CREATE TABLE `bookmark` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` TEXT NOT NULL, `asin` TEXT NOT NULL, `serverId` TEXT, `positionMs` INTEGER NOT NULL, `note` TEXT, `createdAtMs` INTEGER NOT NULL, `deleted` INTEGER NOT NULL)",
            "CREATE INDEX `index_bookmark_userId_asin` ON `bookmark` (`userId`, `asin`)",
            "INSERT INTO progress VALUES ('u1','B1','listening','h',5000,'a.mp3',500,1.25,99,1)",
            "INSERT INTO file_duration VALUES ('h','a.mp3',61000)",
            "INSERT INTO bookmark (userId, asin, serverId, positionMs, note, createdAtMs, deleted) VALUES ('u1','B1',NULL,7000,'later',123,0)",
        )
        val db = Room.databaseBuilder(context, PlayerDb::class.java, "player-v1.db").addMigrations(PlayerDb.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            assertEquals(5000L, dao.progress("u1", "B1")?.positionMs)
            assertTrue(dao.dirtyProgress("u1").single().dirty)
            val d = dao.unreportedDurations().single()
            assertEquals(61000L, d.durationMs)
            assertFalse(d.reported, "a duration learned before v1.1 was never reported, so it goes out once")
            val b = dao.unsyncedBookmarks("u1").single()
            assertEquals("later", b.note)
            assertNull(b.clientId, "gets its client_id when it is next sent")
        } finally {
            db.close()
        }
    }

    @Test fun libraryCacheKeepsItsRowsAndAddsFinishedAt() = runBlocking<Unit> {
        v1(
            "library-v1.db",
            "CREATE TABLE `cache_entry` (`key` TEXT NOT NULL, `json` TEXT NOT NULL, `fetchedAtMs` INTEGER NOT NULL, PRIMARY KEY(`key`))",
            "CREATE TABLE `library_entry` (`userId` TEXT NOT NULL, `asin` TEXT NOT NULL, `bookJson` TEXT NOT NULL, `status` TEXT NOT NULL, `releaseHash` TEXT, `positionMs` INTEGER NOT NULL, `filePath` TEXT, `filePositionMs` INTEGER NOT NULL, `speed` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`, `asin`))",
            "INSERT INTO cache_entry VALUES ('chapters:B1','{}',5)",
            "INSERT INTO library_entry VALUES ('u1','B1','{}','finished',NULL,0,NULL,0,1.0,42)",
        )
        val db = Room.databaseBuilder(context, AudiobooksDb::class.java, "library-v1.db")
            .addMigrations(AudiobooksDb.MIGRATION_1_2).fallbackToDestructiveMigration(dropAllTables = true).allowMainThreadQueries().build()
        try {
            assertEquals("{}", db.cacheDao().get("chapters:B1")?.json, "offline caches survive the upgrade")
            val row = db.libraryDao().get("u1", "B1")!!
            assertEquals(42L, row.updatedAt)
            assertNull(row.finishedAtMs)
            assertEquals(mapOf("B1" to 42L), db.libraryDao().finishedAtMs("u1"), "an entry without finished_at falls back to its last write")
        } finally {
            db.close()
        }
    }
}
