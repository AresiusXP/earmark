package xyz.aresius.audiobooks.feature.debrid

import app.cash.turbine.test
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
import xyz.aresius.audiobooks.core.network.ErrorCode
import xyz.aresius.audiobooks.core.network.Me
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.feature.bittorrent.BitTorrentViewModel
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.MainDispatcherRule
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.awaitState

class DebridAndBitTorrentViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val server = FakeServer()
    private val app = TestApp(server)

    @AfterTest fun tearDown() = server.close()

    private fun listWith(torbox: Boolean) =
        """{"providers":[${Samples.debrid("torbox", torbox)},${Samples.debrid("realdebrid", false)}]}"""

    @Test fun loadsBothProvidersWithTheirStatus() = runBlocking<Unit> {
        server.onJson("GET", "/v1/me/debrid", listWith(true))
        val vm = DebridViewModel(app.debrid)
        vm.state.test {
            var s = awaitItem()
            while (s.load !is Load.Content) s = awaitItem()
            assertEquals(listOf(Provider.TorBox, Provider.RealDebrid), s.providers.map { it.provider })
            assertTrue(s.providers[0].configured)
            assertFalse(s.providers[1].configured)
            assertTrue(s.hasWorkingKey)
        }
    }

    @Test fun aFailedLoadCanBeRetried() = runBlocking<Unit> {
        server.on("GET", "/v1/me/debrid", FakeServer.error(500, "upstream_failed"), FakeServer.json(listWith(false)))
        val vm = DebridViewModel(app.debrid)
        assertIs<Load.Failed>(vm.state.awaitState { it.load is Load.Failed }.load)
        vm.refresh()
        assertIs<Load.Content<*>>(vm.state.awaitState { it.load is Load.Content<*> }.load)
    }

    @Test fun aValidKeyIsStoredAndClearedFromTheField() = runBlocking<Unit> {
        server.onJson("GET", "/v1/me/debrid", listWith(false))
        server.onJson("PUT", "/v1/me/debrid/torbox", Samples.debrid("torbox"))
        val vm = DebridViewModel(app.debrid)
        vm.state.awaitState { it.load is Load.Content<*> }
        vm.startEditing(Provider.TorBox)
        vm.onKeyChange(Provider.TorBox, " tb-secret-key ")
        vm.save(Provider.TorBox)
        val row = vm.state.awaitState { it.providers[0].status?.isOk == true }.providers[0]
        assertFalse(row.editing)
        assertEquals("", row.keyInput, "the key is dropped once sent")
        assertEquals("""{"api_key":"tb-secret-key"}""", server.lastBody("PUT", "/v1/me/debrid/torbox"))
    }

    @Test fun aRejectedKeyStaysInTheFieldWithTheTypedError() = runBlocking<Unit> {
        server.onJson("GET", "/v1/me/debrid", listWith(false))
        server.on("PUT", "/v1/me/debrid/realdebrid", FakeServer.error(400, "bad_debrid_key", "Real-Debrid rejected the key"))
        val vm = DebridViewModel(app.debrid)
        vm.state.awaitState { it.load is Load.Content<*> }
        vm.startEditing(Provider.RealDebrid)
        vm.onKeyChange(Provider.RealDebrid, "wrong-key-123")
        vm.save(Provider.RealDebrid)
        val row = vm.state.awaitState { it.providers[1].error != null }.providers[1]
        assertEquals(ErrorCode.BadDebridKey, (row.error as ApiError.Http).code)
        assertTrue(row.editing)
        assertEquals("wrong-key-123", row.keyInput)
        assertFalse(row.configured)
        vm.onKeyChange(Provider.RealDebrid, "another-key-1")
        assertNull(vm.state.value.providers[1].error)
    }

    @Test fun tooShortKeysNeverReachTheServer() = runBlocking<Unit> {
        server.onJson("GET", "/v1/me/debrid", listWith(false))
        val vm = DebridViewModel(app.debrid)
        vm.state.awaitState { it.load is Load.Content<*> }
        vm.startEditing(Provider.TorBox)
        vm.onKeyChange(Provider.TorBox, "short")
        assertFalse(vm.state.value.providers[0].canSave)
        vm.save(Provider.TorBox)
        assertTrue(server.requestsTo("PUT", "/v1/me/debrid/torbox").isEmpty())
    }

    @Test fun recheckUpdatesTheStatusAndRemoveClearsIt() = runBlocking<Unit> {
        server.onJson("GET", "/v1/me/debrid", listWith(true))
        server.onJson("POST", "/v1/me/debrid/torbox", Samples.debrid("torbox", status = "invalid"))
        server.on("DELETE", "/v1/me/debrid/torbox", FakeServer.empty())
        val vm = DebridViewModel(app.debrid)
        vm.state.awaitState { it.load is Load.Content<*> }
        vm.recheck(Provider.TorBox)
        assertEquals("invalid", vm.state.awaitState { it.providers[0].status?.status == "invalid" }.providers[0].status?.status)
        assertFalse(vm.state.value.hasWorkingKey)
        vm.remove(Provider.TorBox)
        assertFalse(vm.state.awaitState { !it.providers[0].configured }.providers[0].configured)
    }

    // --- BitTorrent ----------------------------------------------------------------------------------------

    private fun btVm(me: String = Samples.ME_ADMIN): BitTorrentViewModel {
        runBlocking { app.me.set(AudiobooksJson.decodeFromString(Me.serializer(), me)) }
        return BitTorrentViewModel(app.me)
    }

    @Test fun bitTorrentIsOffByDefaultAndTurningItOnNeedsTheWarning() = runBlocking<Unit> {
        val vm = btVm()
        assertFalse(vm.state.value.enabled)
        vm.onSwitch(true)
        assertTrue(vm.state.value.warningVisible)
        assertFalse(vm.state.value.enabled)
        vm.dismissWarning()
        assertFalse(vm.state.value.warningVisible)
        assertTrue(server.requestsTo("PATCH", "/v1/me").isEmpty(), "nothing is sent until the warning is accepted")
    }

    @Test fun acceptingTheWarningSetsP2pAndASourceModeThatAllowsIt() = runBlocking<Unit> {
        server.onJson("PATCH", "/v1/me", Samples.ME_USER.replace(""""p2p_enabled":false""", """"p2p_enabled":true""").replace(""""source_mode":"debrid"""", """"source_mode":"auto""""))
        val vm = btVm(Samples.ME_USER) // source_mode = debrid (never BitTorrent)
        vm.onSwitch(true)
        vm.confirmWarning()
        assertTrue(vm.state.awaitState { it.enabled }.enabled)
        assertEquals("""{"settings":{"source_mode":"auto","p2p_enabled":true}}""", server.lastBody("PATCH", "/v1/me"))
    }

    @Test fun turningItOffIsImmediateAndAFailureKeepsTheOldValue() = runBlocking<Unit> {
        val on = Samples.ME_ADMIN.replace(""""p2p_enabled":false""", """"p2p_enabled":true""")
        val vm = btVm(on)
        assertTrue(vm.state.value.enabled)
        server.on("PATCH", "/v1/me", FakeServer.error(500, "upstream_failed"))
        vm.onSwitch(false)
        val s = vm.state.awaitState { it.error != null }
        assertTrue(s.enabled)
        assertFalse(s.saving)
        server.onJson("PATCH", "/v1/me", Samples.ME_ADMIN)
        vm.consumeError()
        vm.onSwitch(false)
        assertFalse(vm.state.awaitState { !it.enabled }.enabled)
    }
}
