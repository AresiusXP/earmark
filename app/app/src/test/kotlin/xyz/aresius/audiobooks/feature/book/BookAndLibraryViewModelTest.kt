package xyz.aresius.audiobooks.feature.book

import androidx.lifecycle.SavedStateHandle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.LibraryStatus
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.ReleaseView
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.feature.library.LibraryViewModel
import xyz.aresius.audiobooks.testutil.FakeLocalFiles
import xyz.aresius.audiobooks.testutil.FakePlayerControls
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.MainDispatcherRule
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.awaitState

class BookAndLibraryViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val server = FakeServer()
    private val app = TestApp(server)
    private val asin = "B000000001"

    @AfterTest fun tearDown() = server.close()

    private val controls = FakePlayerControls()
    private val localFiles = FakeLocalFiles()

    private fun bookVm() = BookViewModel(SavedStateHandle(mapOf("asin" to asin)), app.catalog, app.library, controls, localFiles, app.me)

    @Test fun loadsTheBookAndItsRankedReleases() = runBlocking<Unit> {
        server.onJson("GET", "/v1/books/$asin", Samples.bookDetail(releases = Samples.release("a".repeat(40)) + "," + Samples.release("b".repeat(40))))
        server.on("GET", "/v1/me/library/$asin", FakeServer.error(404, "not_found"))
        val s = bookVm().state.awaitState { it.detail is Load.Content<*> }
        val detail = (s.detail as Load.Content).value
        assertEquals("Pride and Prejudice", detail.book.title)
        assertEquals(listOf("a".repeat(40), "b".repeat(40)), detail.releases.map { it.infoHash })
        assertNull(s.libraryEntry)
    }

    @Test fun aFailureIsRetryable() = runBlocking<Unit> {
        server.on("GET", "/v1/books/$asin", FakeServer.error(404, "not_found", "unknown book"), FakeServer.json(Samples.bookDetail()))
        val vm = bookVm()
        assertIs<ApiError.Http>((vm.state.awaitState { it.detail is Load.Failed }.detail as Load.Failed).error)
        vm.load()
        vm.state.awaitState { it.detail is Load.Content<*> }
    }

    @Test fun addAndRemoveFromTheLibrary() = runBlocking<Unit> {
        server.onJson("GET", "/v1/books/$asin", Samples.bookDetail())
        val vm = bookVm()
        vm.state.awaitState { it.detail is Load.Content<*> }
        server.onJson("PUT", "/v1/me/library/$asin", Samples.libraryEntry(asin, "saved"))
        vm.toggleLibrary()
        assertEquals("saved", vm.state.awaitState { it.libraryEntry != null }.libraryEntry?.status)
        server.on("DELETE", "/v1/me/library/$asin", FakeServer.empty())
        vm.toggleLibrary()
        assertNull(vm.state.awaitState { it.libraryEntry == null && !it.libraryBusy }.libraryEntry)
    }

    @Test fun aLibraryFailureIsReported() = runBlocking<Unit> {
        server.onJson("GET", "/v1/books/$asin", Samples.bookDetail())
        val vm = bookVm()
        vm.state.awaitState { it.detail is Load.Content<*> }
        server.on("PUT", "/v1/me/library/$asin", FakeServer.error(409, "limit_reached"))
        vm.toggleLibrary()
        assertTrue(vm.state.awaitState { it.error != null }.error is ApiError.Http)
        vm.consumeError()
        vm.state.awaitState { it.error == null }
    }

    // --- usable-via chips ------------------------------------------------------------------------------------

    private fun release(usable: String, cached: String) = AudiobooksJson.decodeFromString(ReleaseView.serializer(), Samples.release(usable = usable, cached = cached))

    @Test fun usableViaChips() {
        assertEquals(listOf(UsableVia.Debrid(Provider.TorBox)), release("""{"debrid":["torbox"],"p2p":false}""", "{}").usableVia())
        assertEquals(
            listOf(UsableVia.Debrid(Provider.RealDebrid), UsableVia.BitTorrent),
            release("""{"debrid":["realdebrid"],"p2p":true}""", "{}").usableVia(),
        )
        assertEquals(listOf(UsableVia.BitTorrent), release("""{"debrid":[],"p2p":true}""", "{}").usableVia())
        assertEquals(listOf(UsableVia.NeedsKey), release("""{"debrid":[],"p2p":false}""", "{}").usableVia())
        assertEquals(listOf(UsableVia.NeedsKey), release("""{"debrid":["alldebrid"],"p2p":false}""", "{}").usableVia(), "an unknown provider is not usable")
    }

    @Test fun cacheBadgesPerProviderUnknownIsOmitted() {
        val badges = release("""{"debrid":[],"p2p":false}""", """{"torbox":true,"realdebrid":false}""").cacheBadges()
        assertEquals(listOf(CacheBadge(Provider.TorBox, true), CacheBadge(Provider.RealDebrid, false)), badges)
        assertTrue(release("""{"debrid":[],"p2p":false}""", "{}").cacheBadges().isEmpty())
    }

    // --- library ---------------------------------------------------------------------------------------------

    @Test fun libraryRefreshFilterAndOfflineCopy() = runBlocking<Unit> {
        server.onJson(
            "GET", "/v1/me/library",
            """{"entries":[${Samples.libraryEntry("B000000001", "listening", 30)},${Samples.libraryEntry("B000000002", "saved", 20)},${Samples.libraryEntry("B000000003", "finished", 10)}]}""",
        )
        val vm = LibraryViewModel(app.library)
        val s = vm.state.awaitState { it.loaded }
        assertEquals(3, s.visible.size)
        vm.setFilter(LibraryStatus.SAVED)
        assertEquals(listOf("B000000002"), vm.state.awaitState { it.visible.size == 1 }.visible.map { it.book.asin })
        vm.setFilter(null)
        server.on("GET", "/v1/me/library", FakeServer.error(503, "upstream_failed", null, "Retry-After" to "0"))
        vm.refresh()
        val after = vm.state.awaitState { it.error != null && !it.refreshing }
        assertEquals(3, after.entries.size, "the cached copy stays visible")
    }

    @Test fun libraryFirstLoadFailureIsAnErrorWithEmptyList() = runBlocking<Unit> {
        server.on("GET", "/v1/me/library", FakeServer.error(500, "upstream_failed"))
        val s = LibraryViewModel(app.library).state.awaitState { it.loaded }
        assertTrue(s.entries.isEmpty())
        assertIs<ApiError.Http>(s.error)
    }

    @Test fun comingBackAsksTheServerAgainSoAKeyAddedMeanwhileIsSeen() = runBlocking<Unit> {
        val noKey = Samples.bookDetail(releases = Samples.release(usable = """{"debrid":[],"p2p":false}""", cached = "{}"))
        server.on("GET", "/v1/books/$asin", FakeServer.json(noKey), FakeServer.json(Samples.bookDetail()))
        server.on("GET", "/v1/me/library/$asin", FakeServer.error(404, "not_found"))
        val vm = bookVm()
        assertEquals(PlayAvailability.NeedsKey, vm.state.awaitState { it.detail is Load.Content<*> && !it.refreshing }.availability)
        app.now += 6_000 // the user went to Settings and added a key; a page only seconds old is still served from the copy
        vm.onResumed()
        vm.state.awaitState { it.availability is PlayAvailability.Playable }
        assertEquals(2, server.requestsTo("GET", "/v1/books/$asin").size, "one load, one more on return: no extra 'refresh once' request")
    }

    @Test fun theFirstResumeDoesNotLoadTwice() = runBlocking<Unit> {
        server.onJson("GET", "/v1/books/$asin", Samples.bookDetail())
        val vm = bookVm()
        vm.onResumed() // the screen's first ON_RESUME arrives while the first load is still running
        vm.state.awaitState { it.detail is Load.Content<*> && !it.refreshing }
        assertEquals(1, server.requestsTo("GET", "/v1/books/$asin").size)
    }
}
