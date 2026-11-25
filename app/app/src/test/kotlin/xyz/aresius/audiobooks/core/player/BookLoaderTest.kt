package xyz.aresius.audiobooks.core.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import xyz.aresius.audiobooks.core.data.CatalogRepository
import xyz.aresius.audiobooks.core.download.LocalFiles
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.BookDetail
import xyz.aresius.audiobooks.core.network.Chapter
import xyz.aresius.audiobooks.core.network.Chapters
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.ReleaseView
import xyz.aresius.audiobooks.core.network.TorrentFile
import xyz.aresius.audiobooks.core.network.Usable
import xyz.aresius.audiobooks.testutil.MemoryCacheDao
import xyz.aresius.audiobooks.testutil.memorySessionStore
import xyz.aresius.audiobooks.testutil.testSession

class BookLoaderTest {
    private class FakeCatalog(var detail: ApiResult<BookDetail>, var chapters: ApiResult<Chapters>) : CatalogRepository {
        override suspend fun discover(page: Int, refresh: Boolean) = ApiResult.Success(emptyList<Book>())
        override suspend fun search(query: String, refresh: Boolean) = ApiResult.Success(emptyList<Book>())
        override suspend fun book(asin: String, refresh: Boolean) = detail
        override suspend fun chapters(asin: String) = chapters
    }

    private class FakeLocal(val files: MutableMap<String, File> = HashMap()) : LocalFiles {
        override fun localFile(asin: String, releaseHash: String, path: String) = files["$releaseHash/$path"]
        override fun hasDownloads(asin: String): Flow<Boolean> = flowOf(files.isNotEmpty())
    }

    private val files = listOf(TorrentFile(0, "B/1.mp3", 1000), TorrentFile(1, "B/2.mp3", 1000))
    private fun release(hash: String, debrid: List<String> = listOf("torbox"), cached: Boolean = true, fileList: List<TorrentFile> = files) =
        ReleaseView(infoHash = hash, files = fileList, cached = mapOf("torbox" to cached), usable = Usable(debrid = debrid))

    private val dao = FakePlayerDao()
    private val remote = FakeProgressRemote()
    private val sync = ProgressSync(dao, remote, Counter())
    private val local = FakeLocal()
    private val cache = MemoryCacheDao()
    private var preferred: Provider? = null
    private val sessions = memorySessionStore(testSession("http://x", "u1"))

    private fun loader(releases: List<ReleaseView>, chapters: ApiResult<Chapters> = ApiResult.Success(Chapters("B001", 0, emptyList()))) =
        BookLoader(FakeCatalog(ApiResult.Success(BookDetail(testBook("B001"), null, releases)), chapters), local, sync, dao, cache, sessions, { preferred }, { 5L })

    @Test
    fun `streams the best usable release when nothing is local`() = runTest {
        val r = loader(listOf(release("a", cached = false), release("b"))).load("B001", null)
        val plan = assertIs<LoadResult.Ready>(r).plan
        assertEquals("b", plan.release.infoHash)
        assertEquals(Provider.TorBox, plan.provider)
        assertTrue(plan.needsStream)
        assertEquals(listOf(0, 1), plan.files.map { it.index })
    }

    @Test
    fun `local files win over streaming, per file`() = runTest {
        val f = File("/data/1.mp3")
        local.files["a/B/1.mp3"] = f
        val plan = assertIs<LoadResult.Ready>(loader(listOf(release("a"))).load("B001", null)).plan
        assertEquals(f, plan.files[0].local)
        assertNull(plan.files[1].local)
        assertTrue(plan.needsStream)
        local.files["a/B/2.mp3"] = File("/data/2.mp3")
        assertTrue(!assertIs<LoadResult.Ready>(loader(listOf(release("a"))).load("B001", null)).plan.needsStream)
    }

    @Test
    fun `fully downloaded files play without any key`() = runTest {
        val noKey = release("a", debrid = emptyList())
        local.files["a/B/1.mp3"] = File("/d/1.mp3")
        local.files["a/B/2.mp3"] = File("/d/2.mp3")
        val plan = assertIs<LoadResult.Ready>(loader(listOf(noKey)).load("B001", null)).plan
        assertNull(plan.provider)
        assertTrue(plan.files.all { it.local != null })
    }

    @Test
    fun `no key and nothing downloaded asks for a source`() = runTest {
        val r = loader(listOf(release("a", debrid = emptyList()))).load("B001", null)
        assertEquals(FailureKind.NeedsSource, assertIs<LoadResult.Failure>(r).kind)
    }

    @Test
    fun `no releases and no network are told apart`() = runTest {
        assertEquals(FailureKind.NoReleases, assertIs<LoadResult.Failure>(loader(emptyList()).load("B001", null)).kind)
        val offline = BookLoader(FakeCatalog(ApiResult.Failure(ApiError.Network), ApiResult.Failure(ApiError.Network)), local, sync, dao, cache, sessions, { null }, { 5L })
        assertEquals(FailureKind.Network, assertIs<LoadResult.Failure>(offline.load("B001", null)).kind)
    }

    @Test
    fun `signed out is its own failure`() = runTest {
        val signedOut = BookLoader(FakeCatalog(ApiResult.Failure(ApiError.Network), ApiResult.Failure(ApiError.Network)), local, sync, dao, cache, memorySessionStore(), { null }, { 5L })
        assertEquals(FailureKind.NotSignedIn, assertIs<LoadResult.Failure>(signedOut.load("B001", null)).kind)
    }

    @Test
    fun `the remembered release is used and a request beats it`() = runTest {
        val releases = listOf(release("a"), release("b"), release("c"))
        val l = loader(releases)
        l.remember("u1", "B001", releases[1], Provider.TorBox)
        assertEquals("b", assertIs<LoadResult.Ready>(l.load("B001", null)).plan.release.infoHash)
        assertEquals("c", assertIs<LoadResult.Ready>(l.load("B001", "c")).plan.release.infoHash)
        assertEquals("b", assertIs<LoadResult.Ready>(l.load("B001", "unknown")).plan.release.infoHash)
    }

    @Test
    fun `learned durations come with the files and the release of the library entry is the fallback choice`() = runTest {
        dao.putDuration(FileDurationEntity("b", "B/1.mp3", 123_000))
        remote.entries["B001"] = xyz.aresius.audiobooks.core.network.LibraryEntry(testBook("B001"), "listening", "b", 5000, "B/2.mp3", 500, 1.25, 10)
        val plan = assertIs<LoadResult.Ready>(loader(listOf(release("a"), release("b"))).load("B001", null)).plan
        assertEquals("b", plan.release.infoHash)
        assertEquals(123_000L, plan.files[0].durationMs)
        assertNull(plan.files[1].durationMs)
        assertEquals("B/2.mp3", plan.resume!!.filePath)
        assertEquals(1.25, plan.resume.speed)
    }

    @Test
    fun `chapters are cached for offline use`() = runTest {
        val chapters = Chapters("B001", 100, listOf(Chapter("One", 0, 100)))
        assertEquals("One", assertIs<LoadResult.Ready>(loader(listOf(release("a")), ApiResult.Success(chapters)).load("B001", null)).plan.chapters!!.chapters.single().title)
        val offline = loader(listOf(release("a")), ApiResult.Failure(ApiError.Network))
        assertEquals("One", assertIs<LoadResult.Ready>(offline.load("B001", null)).plan.chapters!!.chapters.single().title)
    }

    @Test
    fun `a release without a file list from the server waits for the provider's list`() = runTest {
        val plan = assertIs<LoadResult.Ready>(loader(listOf(release("a", fileList = emptyList()))).load("B001", null)).plan
        assertTrue(plan.files.isEmpty())
        val withFiles = loader(emptyList()).withProviderFiles(plan, files)
        assertEquals(2, withFiles.files.size)
        assertEquals(listOf(0, 1), withFiles.files.map { it.index })
    }
}
