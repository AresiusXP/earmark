package xyz.aresius.audiobooks.feature.discover

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import xyz.aresius.audiobooks.core.data.CatalogRepositoryImpl
import xyz.aresius.audiobooks.core.data.NetworkEvents
import org.junit.Rule
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Me
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.core.player.PlayerPhase
import xyz.aresius.audiobooks.core.player.PlayerState
import xyz.aresius.audiobooks.testutil.FakePlayerControls
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.MainDispatcherRule
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.awaitState

class DiscoverViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val server = FakeServer()
    private val app = TestApp(server)
    private val player = FakePlayerControls()

    @AfterTest fun tearDown() = server.close()

    private fun vm(hour: Int = 20): DiscoverViewModel {
        server.on("GET", "/v1/me/library", FakeServer.error(500, "upstream_failed"))
        return DiscoverViewModel(app.catalog, app.library, app.me, player) { hour }
    }

    @Test fun greetingFollowsTheHour() {
        assertEquals(Greeting.Night, greetingForHour(3))
        assertEquals(Greeting.Night, greetingForHour(4))
        assertEquals(Greeting.Morning, greetingForHour(5))
        assertEquals(Greeting.Morning, greetingForHour(11))
        assertEquals(Greeting.Afternoon, greetingForHour(12))
        assertEquals(Greeting.Afternoon, greetingForHour(17))
        assertEquals(Greeting.Evening, greetingForHour(18))
        assertEquals(Greeting.Evening, greetingForHour(22))
        assertEquals(Greeting.Night, greetingForHour(23))
        assertEquals(Greeting.Night, greetingForHour(0))
        (0..23).forEach { greetingForHour(it) }
    }

    @Test fun spanishKeepsGoodAfternoonUntilEight() {
        assertEquals(20, eveningFromHour("es"))
        assertEquals(18, eveningFromHour("en"))
        assertEquals(18, eveningFromHour("nl"))
        assertEquals(Greeting.Morning, greetingForHour(5, eveningFromHour("es")))
        assertEquals(Greeting.Morning, greetingForHour(11, eveningFromHour("es")))
        assertEquals(Greeting.Afternoon, greetingForHour(12, eveningFromHour("es")))
        assertEquals(Greeting.Afternoon, greetingForHour(19, eveningFromHour("es")))
        assertEquals(Greeting.Evening, greetingForHour(20, eveningFromHour("es")))
        assertEquals(Greeting.Evening, greetingForHour(22, eveningFromHour("es")))
        assertEquals(Greeting.Night, greetingForHour(23, eveningFromHour("es")))
        assertEquals(Greeting.Night, greetingForHour(4, eveningFromHour("es")))
        for (lang in listOf("en", "nl", "es")) {
            assertEquals(Greeting.entries.toSet(), (0..23).map { greetingForHour(it, eveningFromHour(lang)) }.toSet(), lang)
        }
    }

    @Test fun shelvesAreChunksOfEight() {
        assertEquals(listOf(8, 8, 3), shelves((1..19).toList()).map { it.size })
        assertTrue(shelves(emptyList<Int>()).isEmpty())
    }

    @Test fun loadsBooksAndGreetsByName() = runBlocking<Unit> {
        app.me.set(AudiobooksJson.decodeFromString(Me.serializer(), Samples.ME_ADMIN))
        server.onJson("GET", "/v1/discover", Samples.books("B000000001", "B000000002"))
        val s = vm(hour = 8).state.awaitState { it.books is Load.Content<*> }
        assertEquals(2, (s.books as Load.Content).value.size)
        assertEquals(Greeting.Morning, s.greeting)
        assertEquals("Ada", s.name)
        assertNull(s.continueListening)
    }

    @Test fun theHeroIsTheLatestListeningEntryWithProgress() = runBlocking<Unit> {
        server.onJson("GET", "/v1/discover", Samples.books("B000000001"))
        server.onJson(
            "GET", "/v1/me/library",
            """{"entries":[${Samples.libraryEntry("B000000001", "saved", 50, position = 0)},${Samples.libraryEntry("B000000002", "listening", 30, position = 0)},${Samples.libraryEntry("B000000003", "listening", 20, position = 900000)},${Samples.libraryEntry("B000000004", "listening", 10, position = 100000)}]}""",
        )
        val vm = DiscoverViewModel(app.catalog, app.library, app.me, player) { 20 }
        val s = vm.state.awaitState { it.continueListening != null }
        assertEquals("B000000003", s.continueListening?.book?.asin, "saved and zero-progress entries are no hero")
    }

    @Test fun morePagesAppendWithoutDuplicatesUntilAnEmptyPage() = runBlocking<Unit> {
        server.onRequest("GET", "/v1/discover") { req ->
            when (req.url.queryParameter("page")) {
                "0" -> FakeServer.json(Samples.books("B000000001", "B000000002"))
                "1" -> FakeServer.json(Samples.books("B000000002", "B000000003"))
                else -> FakeServer.json(Samples.books())
            }
        }
        val vm = vm()
        vm.state.awaitState { it.books is Load.Content<*> }
        vm.loadMore()
        assertEquals(listOf("B000000001", "B000000002", "B000000003"), (vm.state.awaitState { (it.books as? Load.Content)?.value?.size == 3 }.books as Load.Content).value.map { it.asin })
        vm.loadMore()
        assertFalse(vm.state.awaitState { !it.canLoadMore }.canLoadMore)
    }

    @Test fun aFailureIsRetryableAndKeepsOldContentOnRefresh() = runBlocking<Unit> {
        server.on("GET", "/v1/discover", FakeServer.error(500, "upstream_failed"), FakeServer.json(Samples.books("B000000001")))
        val vm = vm()
        assertIs<Load.Failed>(vm.state.awaitState { it.books is Load.Failed }.books)
        vm.load()
        vm.state.awaitState { it.books is Load.Content<*> }
        server.on("GET", "/v1/discover", FakeServer.error(500, "upstream_failed"))
        vm.load()
        val s = vm.state.awaitState { !it.refreshing && (it.books as? Load.Content)?.stale == true }
        assertEquals(1, (s.books as Load.Content).value.size)
    }

    private class FakeNetwork : NetworkEvents {
        val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val available: SharedFlow<Unit> = flow
    }

    private fun refreshingVm(net: NetworkEvents = FakeNetwork(), now: () -> Long = { app.now }): DiscoverViewModel {
        server.on("GET", "/v1/me/library", FakeServer.error(500, "upstream_failed"))
        return DiscoverViewModel(app.catalog, app.library, app.me, player, net, debounceMs = 50, clock = now) { 20 }
    }

    @Test fun theOfflineBannerGoesAsSoonAsTheNetworkReturns() = runBlocking<Unit> {
        server.on(
            "GET", "/v1/discover",
            FakeServer.json(Samples.books("B000000001")), FakeServer.error(500, "upstream_failed"), FakeServer.json(Samples.books("B000000001", "B000000002")),
        )
        val net = FakeNetwork()
        val vm = refreshingVm(net)
        vm.state.awaitState { it.books is Load.Content<*> && !it.refreshing }
        app.now += CatalogRepositoryImpl.FRESH_MS + 1
        vm.load()
        vm.state.awaitState { !it.refreshing && (it.books as? Load.Content)?.stale == true }
        net.flow.emit(Unit)
        val s = vm.state.awaitState { (it.books as? Load.Content)?.let { c -> !c.stale && c.value.size == 2 } == true }
        assertFalse((s.books as Load.Content).stale)
    }

    @Test fun comingBackToTheScreenRefreshesASavedCopyOnceDespiteManyTriggers() = runBlocking<Unit> {
        server.on(
            "GET", "/v1/discover",
            FakeServer.json(Samples.books("B000000001")), FakeServer.error(500, "upstream_failed"), FakeServer.json(Samples.books("B000000001", "B000000002")),
        )
        val vm = refreshingVm()
        vm.state.awaitState { it.books is Load.Content<*> && !it.refreshing }
        app.now += CatalogRepositoryImpl.FRESH_MS + 1
        vm.load()
        vm.state.awaitState { !it.refreshing && (it.books as? Load.Content)?.stale == true }
        val before = server.requestsTo("GET", "/v1/discover").size
        repeat(5) { vm.onResumed() }
        vm.state.awaitState { (it.books as? Load.Content)?.stale == false && !it.refreshing }
        assertEquals(before + 1, server.requestsTo("GET", "/v1/discover").size, "the triggers are debounced into one request")
    }

    @Test fun aFreshPageIsLeftAloneWhenTheScreenComesBack() = runBlocking<Unit> {
        server.onJson("GET", "/v1/discover", Samples.books("B000000001"))
        val vm = refreshingVm()
        vm.state.awaitState { it.books is Load.Content<*> && !it.refreshing }
        vm.onResumed()
        Thread.sleep(300)
        assertEquals(1, server.requestsTo("GET", "/v1/discover").size)
    }

    @Test fun anErrorScreenRetriesByItselfWhenTheNetworkReturns() = runBlocking<Unit> {
        server.on("GET", "/v1/discover", FakeServer.error(500, "upstream_failed"), FakeServer.json(Samples.books("B000000001")))
        val net = FakeNetwork()
        val vm = refreshingVm(net)
        vm.state.awaitState { it.books is Load.Failed }
        net.flow.emit(Unit)
        assertEquals(1, (vm.state.awaitState { it.books is Load.Content<*> }.books as Load.Content).value.size)
    }

    @Test fun theHeroTogglesAndShowsPauseWhileItsBookPlays() = runBlocking<Unit> {
        server.onJson("GET", "/v1/discover", Samples.books("B000000001"))
        server.onJson("GET", "/v1/me/library", """{"entries":[${Samples.libraryEntry("B000000003", "listening", 20, position = 900000)}]}""")
        val vm = DiscoverViewModel(app.catalog, app.library, app.me, player) { 20 }
        var s = vm.state.awaitState { it.continueListening != null }
        assertFalse(s.continueInPlayer, "nothing is loaded: the button starts the book")
        player.mutableState.value = PlayerState(phase = PlayerPhase.Ready, asin = "B000000003", playing = true, positionMs = 1_200_000)
        s = vm.state.awaitState { it.continuePlaying }
        assertTrue(s.continueInPlayer)
        assertEquals(1_200_000L, s.continuePositionMs)
        player.mutableState.value = PlayerState(phase = PlayerPhase.Ready, asin = "B000000003", playing = false)
        s = vm.state.awaitState { it.continueInPlayer && !it.continuePlaying }
        assertFalse(s.continueBusy)
        vm.togglePlay()
        assertEquals(listOf("toggle"), player.calls)
        player.mutableState.value = PlayerState(phase = PlayerPhase.Loading, asin = "B000000003")
        assertTrue(vm.state.awaitState { it.continueBusy }.continueInPlayer)
    }
}
