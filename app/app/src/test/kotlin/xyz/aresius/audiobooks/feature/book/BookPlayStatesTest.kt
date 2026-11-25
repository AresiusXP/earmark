package xyz.aresius.audiobooks.feature.book

import androidx.lifecycle.SavedStateHandle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import xyz.aresius.audiobooks.core.player.FailureKind
import xyz.aresius.audiobooks.core.player.PlayerPhase
import xyz.aresius.audiobooks.core.player.PlayerState
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.testutil.FakeLocalFiles
import xyz.aresius.audiobooks.testutil.FakePlayerControls
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.MainDispatcherRule
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.awaitState

/** What the book page lets the user do about listening: which release, whether Play is possible, what the player is doing. */
class BookPlayStatesTest {
    @get:Rule val main = MainDispatcherRule()

    private val server = FakeServer()
    private val app = TestApp(server)
    private val asin = "B000000001"
    private val controls = FakePlayerControls()
    private val local = FakeLocalFiles()
    private val a = "a".repeat(40)
    private val b = "b".repeat(40)

    @AfterTest fun tearDown() = server.close()

    private fun vm() = BookViewModel(SavedStateHandle(mapOf("asin" to asin)), app.catalog, app.library, controls, local, app.me)

    private fun serve(releases: String) {
        server.onJson("GET", "/v1/books/$asin", Samples.bookDetail(releases = releases))
        server.on("GET", "/v1/me/library/$asin", FakeServer.error(404, "not_found"))
        server.onJson("GET", "/v1/books/$asin/chapters", """{"asin":"$asin","runtime_ms":1000,"chapters":[{"title":"One","start_ms":0,"length_ms":1000}]}""")
    }

    @Test fun `with a debrid key Play streams the best release and starts it`() = runBlocking<Unit> {
        serve(Samples.release(a, cached = """{"torbox":false}""") + "," + Samples.release(b))
        val vm = vm()
        val s = vm.state.awaitState { it.chosen != null }
        assertEquals(b, s.chosen!!.release.infoHash, "cached first")
        assertEquals(PlayAvailability.Playable(xyz.aresius.audiobooks.core.network.Provider.TorBox), s.availability)
        vm.play()
        assertEquals(listOf(asin), controls.plays.map { it.first })
        assertEquals(b, controls.plays.single().second)
        assertEquals("Pride and Prejudice", controls.plays.single().third!!.title)
    }

    @Test fun `the user's pick beats the suggestion and is what plays`() = runBlocking<Unit> {
        serve(Samples.release(a) + "," + Samples.release(b))
        val vm = vm()
        val release = (vm.state.awaitState { it.detail is Load.Content<*> }.detail as Load.Content).value.releases.last()
        vm.choose(release)
        assertEquals(b, vm.state.awaitState { it.chosen?.release?.infoHash == b }.chosen!!.release.infoHash)
        vm.play()
        assertEquals(b, controls.plays.single().second)
    }

    @Test fun `the remembered release of an earlier listen is the default`() = runBlocking<Unit> {
        controls.remembered = b
        serve(Samples.release(a) + "," + Samples.release(b))
        assertEquals(b, vm().state.awaitState { it.chosen?.release?.infoHash == b }.chosen!!.release.infoHash)
    }

    @Test fun `no key and no downloads asks for a key and Play does nothing`() = runBlocking<Unit> {
        serve(Samples.release(a, usable = """{"debrid":[],"p2p":false}"""))
        val vm = vm()
        assertEquals(PlayAvailability.NeedsKey, vm.state.awaitState { it.chosen != null }.availability)
        vm.play()
        assertEquals(0, controls.plays.size)
    }

    @Test fun `downloaded files make a book playable without a key`() = runBlocking<Unit> {
        serve(Samples.release(a, usable = """{"debrid":[],"p2p":false}"""))
        local.has.value = true
        val s = vm().state.awaitState { it.availability is PlayAvailability.Playable }
        assertNull((s.availability as PlayAvailability.Playable).provider)
    }

    @Test fun `no releases at all`() = runBlocking<Unit> {
        serve("")
        server.onJson("GET", "/v1/books/$asin", Samples.bookDetail(releases = "").replace("\"releases\":[]", "\"releases\":[]"))
        assertEquals(PlayAvailability.NoReleases, vm().state.awaitState { it.detail is Load.Content<*> }.availability)
    }

    @Test fun `the catalog's chapters are listed`() = runBlocking<Unit> {
        serve(Samples.release(a))
        val s = vm().state.awaitState { it.chapters is Load.Content<*> }
        assertEquals("One", (s.chapters as Load.Content).value.chapters.single().title)
    }

    @Test fun `the player's preparing and failed states for this book show on the page, not those of another book`() = runBlocking<Unit> {
        serve(Samples.release(a))
        val vm = vm()
        vm.state.awaitState { it.chosen != null }
        controls.mutableState.value = PlayerState(phase = PlayerPhase.Preparing(40, null), asin = asin)
        assertEquals(PlayerPhase.Preparing(40, null), vm.state.awaitState { it.playerPhase != null }.playerPhase)
        controls.mutableState.value = PlayerState(phase = PlayerPhase.Failed(FailureKind.NoDebridKey), asin = asin)
        assertIs<PlayerPhase.Failed>(vm.state.awaitState { it.playerPhase is PlayerPhase.Failed }.playerPhase)
        controls.mutableState.value = PlayerState(phase = PlayerPhase.Preparing(1, null), asin = "OTHER")
        assertNull(vm.state.awaitState { it.playerPhase == null }.playerPhase)
        controls.mutableState.value = PlayerState(phase = PlayerPhase.Ready, asin = asin)
        assertNull(vm.state.awaitState { it.playerPhase == null }.playerPhase)
    }
}
