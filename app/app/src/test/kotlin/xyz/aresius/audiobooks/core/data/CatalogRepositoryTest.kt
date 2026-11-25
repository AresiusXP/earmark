package xyz.aresius.audiobooks.core.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.ErrorCode
import xyz.aresius.audiobooks.core.network.HomeShelfKind
import xyz.aresius.audiobooks.core.network.errorOrNull
import xyz.aresius.audiobooks.core.network.valueOrNull
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.TestApp

class CatalogRepositoryTest {
    private val server = FakeServer()
    private val app = TestApp(server)

    @AfterTest fun tearDown() = server.close()

    @Test fun aFreshCachedAnswerNeedsNoRequest() = runBlocking<Unit> {
        server.onJson("GET", "/v1/home", Samples.homeOf("B000000001", "B000000002"))
        assertEquals(2, app.catalog.home().valueOrNull?.single()?.books?.size)
        app.now += 60_000
        val again = app.catalog.home() as ApiResult.Success
        assertEquals(2, again.value.single().books.size)
        assertFalse(again.stale)
        assertEquals(1, server.requestsTo("GET", "/v1/home").size)
    }

    @Test fun anOldOrForcedAnswerAsksTheServerAgain() = runBlocking<Unit> {
        server.onJson("GET", "/v1/home", Samples.homeOf("B000000001"))
        app.catalog.home()
        app.now += 6 * 60_000
        app.catalog.home()
        app.catalog.home(refresh = true)
        assertEquals(3, server.requestsTo("GET", "/v1/home").size)
    }

    @Test fun homeMapsKindsAndSkipsUnknownKindsAndEmptyShelves() = runBlocking<Unit> {
        server.onJson(
            "GET", "/v1/home",
            Samples.home(
                Samples.shelf("a", "more_from_author", "Ann", "B000000001"),
                Samples.shelf("b", "future_kind", null, "B000000002"),
                Samples.shelf("c", "new_releases", null),
                Samples.shelf("d", "genre", "thriller", "B000000003"),
            ),
        )
        val shelves = app.catalog.home().valueOrNull!!
        assertEquals(listOf("a", "d"), shelves.map { it.id })
        assertEquals(listOf(HomeShelfKind.MoreFromAuthor, HomeShelfKind.Genre), shelves.map { it.kind })
        assertEquals("thriller", shelves[1].arg)
    }

    @Test fun aServerWithoutHomeFallsBackToDiscoverAsBestsellers() = runBlocking<Unit> {
        server.on("GET", "/v1/home", FakeServer.error(404, "not_found"))
        server.onJson("GET", "/v1/discover", Samples.books("B000000001"))
        val shelf = app.catalog.home().valueOrNull!!.single()
        assertEquals(HomeShelfKind.Bestsellers, shelf.kind)
        assertEquals("B000000001", shelf.books.single().asin)
        assertEquals("0", server.requestsTo("GET", "/v1/discover").single().url.queryParameter("page"))
    }

    @Test fun aFailedHomeServesTheOldCopyMarkedStale() = runBlocking<Unit> {
        server.onJson("GET", "/v1/home", Samples.homeOf("B000000001"))
        app.catalog.home()
        app.now += 10 * 60_000
        server.on("GET", "/v1/home", FakeServer.error(503, "upstream_failed"))
        server.on("GET", "/v1/discover", FakeServer.error(503, "upstream_failed"))
        val r = app.catalog.home() as ApiResult.Success
        assertTrue(r.stale)
        assertEquals("B000000001", r.value.single().books.single().asin)
    }

    @Test fun aFailureServesTheOldCopyMarkedStale() = runBlocking<Unit> {
        server.onJson("GET", "/v1/search", Samples.books("B000000001"))
        app.catalog.search("austen")
        app.now += 10 * 60_000
        server.on("GET", "/v1/search", FakeServer.error(500, "upstream_failed", "down"))
        val r = app.catalog.search("Austen ") as ApiResult.Success
        assertTrue(r.stale)
        assertEquals("B000000001", r.value.single().asin)
    }

    @Test fun aFailureWithoutACopyIsTheTypedError() = runBlocking<Unit> {
        server.on("GET", "/v1/search", FakeServer.error(400, "invalid_request", "q too short"))
        val e = app.catalog.search("a").errorOrNull
        assertIs<ApiError.Http>(e)
        assertEquals(ErrorCode.InvalidRequest, e.code)
    }

    @Test fun bookDetailKeepsReleasesAndChaptersAreNotCached() = runBlocking<Unit> {
        server.onJson("GET", "/v1/books/B000000001", Samples.bookDetail())
        val d = app.catalog.book("B000000001").valueOrNull!!
        assertEquals(1, d.releases.size)
        server.onJson("GET", "/v1/books/B000000001/chapters", """{"asin":"B000000001","runtime_ms":0,"chapters":[]}""")
        assertTrue(app.catalog.chapters("B000000001").valueOrNull!!.chapters.isEmpty())
        server.on("GET", "/v1/books/B000000404", FakeServer.error(404, "not_found", "unknown"))
        assertEquals(ErrorCode.NotFound, (app.catalog.book("B000000404").errorOrNull as ApiError.Http).code)
    }

    @Test fun aBookPageIsPerUserSoItIsOnlyReusedForSeconds() = runBlocking<Unit> {
        val noKey = Samples.bookDetail(releases = Samples.release(usable = """{"debrid":[],"p2p":false}"""))
        val withKey = Samples.bookDetail()
        server.on("GET", "/v1/books/B000000001", FakeServer.json(noKey), FakeServer.json(withKey))
        assertTrue(app.catalog.book("B000000001").valueOrNull!!.releases.single().usable.debrid.isEmpty())
        app.now += 3_000
        assertTrue(app.catalog.book("B000000001").valueOrNull!!.releases.single().usable.debrid.isEmpty(), "two screens opening the same book share one request")
        assertEquals(1, server.requestsTo("GET", "/v1/books/B000000001").size)
        // The user adds a debrid key in Settings and comes back: the page must not keep saying "no key".
        app.now += 3_000
        assertEquals(listOf("torbox"), app.catalog.book("B000000001").valueOrNull!!.releases.single().usable.debrid)
        assertEquals(2, server.requestsTo("GET", "/v1/books/B000000001").size)
    }

    @Test fun anOldBookPageStillServesOfflineMarkedStale() = runBlocking<Unit> {
        server.onJson("GET", "/v1/books/B000000001", Samples.bookDetail())
        app.catalog.book("B000000001")
        app.now += 60 * 60_000
        server.on("GET", "/v1/books/B000000001", FakeServer.error(503, "upstream_failed"))
        assertTrue((app.catalog.book("B000000001") as ApiResult.Success).stale)
    }

    @Test fun theHttpClientsKeepNoCacheOfTheirOwn() {
        val factory = xyz.aresius.audiobooks.core.network.HttpClientFactory(
            app.sessions, xyz.aresius.audiobooks.core.network.SignedOutEvents(), xyz.aresius.audiobooks.core.network.NetworkTimeouts(), emptyList(),
        )
        assertEquals(null, factory.api().cache)
        assertEquals(null, factory.plain().cache)
    }
}
