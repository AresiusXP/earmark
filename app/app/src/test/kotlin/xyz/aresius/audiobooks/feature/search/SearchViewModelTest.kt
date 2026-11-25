package xyz.aresius.audiobooks.feature.search

import androidx.lifecycle.SavedStateHandle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.MainDispatcherRule
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.awaitState

class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val server = FakeServer()
    private val app = TestApp(server)

    @AfterTest fun tearDown() = server.close()

    @Test fun idleThenTooShortThenResults() = runBlocking<Unit> {
        server.onJson("GET", "/v1/search", Samples.books("B000000001", "B000000002"))
        val vm = SearchViewModel(app.catalog, SavedStateHandle())
        assertEquals(SearchResultsState.Idle, vm.state.value.results)
        vm.onQueryChange("a")
        assertEquals(SearchResultsState.TooShort, vm.state.awaitState { it.results == SearchResultsState.TooShort }.results)
        vm.onQueryChange("austen")
        val results = vm.state.awaitState { (it.results as? SearchResultsState.Results)?.load is Load.Content<*> }.results as SearchResultsState.Results
        assertEquals(2, (results.load as Load.Content).value.size)
        assertEquals("austen", server.requests.single().url.queryParameter("q"))
        vm.onQueryChange("")
        assertEquals(SearchResultsState.Idle, vm.state.awaitState { it.results == SearchResultsState.Idle }.results)
    }

    @Test fun typingFastSendsOneRequestForTheFinalText() = runBlocking<Unit> {
        server.onJson("GET", "/v1/search", Samples.books("B000000001"))
        val vm = SearchViewModel(app.catalog, SavedStateHandle())
        "dune".indices.forEach { vm.onQueryChange("dune".substring(0, it + 1)); Thread.sleep(50) }
        vm.state.awaitState { (it.results as? SearchResultsState.Results)?.load is Load.Content<*> }
        Thread.sleep(600)
        assertEquals(listOf("dune"), server.requests.map { it.url.queryParameter("q") })
    }

    @Test fun anErrorCanBeRetriedAndSkipsTheCache() = runBlocking<Unit> {
        server.on("GET", "/v1/search", FakeServer.error(500, "upstream_failed"), FakeServer.json(Samples.books("B000000001")))
        val vm = SearchViewModel(app.catalog, SavedStateHandle())
        vm.onQueryChange("holmes")
        val failed = vm.state.awaitState { (it.results as? SearchResultsState.Results)?.load is Load.Failed }
        assertIs<ApiError.Http>(((failed.results as SearchResultsState.Results).load as Load.Failed).error)
        vm.retry()
        vm.state.awaitState { (it.results as? SearchResultsState.Results)?.load is Load.Content<*> }
        assertEquals(2, server.requests.size)
    }

    @Test fun theQueryAndItsResultsComeBackFromTheSavedState() = runBlocking<Unit> {
        server.onJson("GET", "/v1/search", Samples.books("B000000001", "B000000002"))
        val handle = SavedStateHandle()
        val first = SearchViewModel(app.catalog, handle)
        first.onQueryChange("  austen ")
        first.state.awaitState { (it.results as? SearchResultsState.Results)?.load is Load.Content<*> }
        // The tab was left and the view model (or the whole process) is gone: a new one is built over the same saved state.
        val again = SearchViewModel(app.catalog, SavedStateHandle(mapOf("query" to handle.get<String>("query"))))
        assertEquals("  austen ", again.state.value.query)
        val results = again.state.awaitState { (it.results as? SearchResultsState.Results)?.load is Load.Content<*> }.results as SearchResultsState.Results
        assertEquals(listOf("B000000001", "B000000002"), (results.load as Load.Content).value.map { it.asin })
        assertEquals(1, server.requestsTo("GET", "/v1/search").size, "served from the catalog copy, no second request")
    }

    @Test fun clearingTheQueryClearsTheSavedOne() = runBlocking<Unit> {
        val handle = SavedStateHandle(mapOf("query" to "dune"))
        server.onJson("GET", "/v1/search", Samples.books("B000000001"))
        val vm = SearchViewModel(app.catalog, handle)
        vm.onQueryChange("")
        assertEquals("", handle.get<String>("query"))
        assertEquals(SearchResultsState.Idle, vm.state.awaitState { it.results == SearchResultsState.Idle }.results)
    }
}
