package xyz.aresius.audiobooks.feature.settings

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import xyz.aresius.audiobooks.core.auth.BuildInfo
import xyz.aresius.audiobooks.core.auth.InputProblem
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Me
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.MainDispatcherRule
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.awaitState

class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val server = FakeServer()
    private val app = TestApp(server)
    private val devices = """{"devices":[{"id":"d1","name":"Pixel","created_at":"2026-11-25T10:00:00Z","current":true},{"id":"d2","name":"Tablet","created_at":"2026-11-20T10:00:00Z","current":false}]}"""

    @AfterTest fun tearDown() = server.close()

    private fun vm(me: String = Samples.ME_ADMIN): SettingsViewModel {
        runBlocking { app.me.set(AudiobooksJson.decodeFromString(Me.serializer(), me)) }
        server.onJson("GET", "/v1/me", me)
        server.onJson("GET", "/v1/me/devices", devices)
        return SettingsViewModel(app.me, app.auth(), app.prefs, BuildInfo("0.3.0", 300, false)).also {
            // `state` is a combine().stateIn() pipeline that other threads (the answers of the first requests) feed too: it reaches a value a
            // moment after the write. Reading `state.value` straight after a write is a race; wait for the value instead (see awaitState).
            runBlocking { it.state.awaitState { s -> s.me != null } }
        }
    }

    private fun patched(json: String, vararg replacements: Pair<String, String>) = replacements.fold(json) { acc, (a, b) -> acc.replace(a, b) }

    @Test fun showsTheProfileAndTheDevices() = runBlocking<Unit> {
        val s = vm().state.awaitState { it.devices is Load.Content<*> }
        assertEquals("Ada", s.me?.displayName)
        assertEquals(listOf("Pixel", "Tablet"), (s.devices as Load.Content).value.map { it.name })
        assertEquals("0.3.0", s.versionName)
    }

    @Test fun regionFormatAndSourceModePatchTheServer() = runBlocking<Unit> {
        val vm = vm()
        server.onJson("PATCH", "/v1/me", patched(Samples.ME_ADMIN, """"region":"us"""" to """"region":"de""""))
        vm.setRegion("de")
        vm.state.awaitState { it.me?.settings?.region == "de" }
        assertEquals("""{"settings":{"region":"de"}}""", server.lastBody("PATCH", "/v1/me"))
        server.onJson("PATCH", "/v1/me", patched(Samples.ME_ADMIN, "\"prefer_format\":\"\"" to "\"prefer_format\":\"mp3\""))
        vm.setPreferredFormat("mp3")
        vm.state.awaitState { it.me?.settings?.preferFormat == "mp3" }
        vm.setRegion("xx")
        vm.setPreferredFormat("flac")
        assertEquals(2, server.requestsTo("PATCH", "/v1/me").size, "unknown values are never sent")
    }

    @Test fun theLastLanguageCannotBeTurnedOff() = runBlocking<Unit> {
        val vm = vm() // languages = [en]
        vm.toggleLanguage("en")
        assertTrue(server.requestsTo("PATCH", "/v1/me").isEmpty())
        server.onJson("PATCH", "/v1/me", patched(Samples.ME_ADMIN, """"languages":["en"]""" to """"languages":["en","nl"]"""))
        vm.toggleLanguage("nl")
        vm.state.awaitState { it.me?.settings?.languages == listOf("en", "nl") }
        assertEquals("""{"settings":{"languages":["en","nl"]}}""", server.lastBody("PATCH", "/v1/me"))
    }

    @Test fun bitTorrentSourceModeNeedsBitTorrentTurnedOn() = runBlocking<Unit> {
        val vm = vm()
        assertFalse(vm.state.value.p2pSourceModeAvailable)
        vm.setSourceMode("p2p")
        assertTrue(server.requestsTo("PATCH", "/v1/me").isEmpty())
        server.onJson("PATCH", "/v1/me", patched(Samples.ME_ADMIN, """"source_mode":"auto"""" to """"source_mode":"debrid""""))
        vm.setSourceMode("debrid")
        vm.state.awaitState { it.me?.settings?.sourceMode == "debrid" }
    }

    @Test fun renameValidatesFirstThenPatches() = runBlocking<Unit> {
        val vm = vm()
        vm.rename("  ")
        assertEquals(InputProblem.NameEmpty, vm.state.awaitState { it.nameProblem != null }.nameProblem)
        assertTrue(server.requestsTo("PATCH", "/v1/me").isEmpty())
        server.onJson("PATCH", "/v1/me", patched(Samples.ME_ADMIN, "Ada" to "Ada L."))
        vm.rename(" Ada L. ")
        assertEquals("Ada L.", vm.state.awaitState { it.me?.displayName == "Ada L." }.me?.displayName)
        vm.state.awaitState { it.nameProblem == null }
        assertEquals("""{"display_name":"Ada L."}""", server.lastBody("PATCH", "/v1/me"))
    }

    @Test fun aLinkCodeIsShownOnceAndDropped() = runBlocking<Unit> {
        val vm = vm()
        server.onJson("POST", "/v1/me/devices/link-code", """{"link_code":"LINK-ONCE","expires_at":"2026-11-25T10:15:00Z"}""")
        vm.createLinkCode()
        assertEquals("LINK-ONCE", vm.state.awaitState { it.linkCode != null }.linkCode?.linkCode)
        vm.dismissLinkCode()
        vm.state.awaitState { it.linkCode == null }
    }

    @Test fun revokingADeviceReloadsTheList() = runBlocking<Unit> {
        val vm = vm()
        vm.state.awaitState { it.devices is Load.Content<*> }
        server.on("DELETE", "/v1/me/devices/d2", FakeServer.empty())
        server.onJson("GET", "/v1/me/devices", """{"devices":[{"id":"d1","name":"Pixel","created_at":"2026-11-25T10:00:00Z","current":true}]}""")
        vm.revokeDevice("d2")
        val s = vm.state.awaitState { (it.devices as? Load.Content)?.value?.size == 1 && it.busyDeviceId == null }
        assertNotNull(s)
    }

    @Test fun dynamicColorIsOffUntilTheUserTurnsItOn() = runBlocking<Unit> {
        val vm = vm()
        assertFalse(vm.state.value.dynamicColor)
        assertFalse(app.prefs.dynamicColor.first())
        vm.setDynamicColor(true)
        assertTrue(vm.state.awaitState { it.dynamicColor }.dynamicColor)
        vm.setDynamicColor(false)
        assertFalse(vm.state.awaitState { !it.dynamicColor }.dynamicColor)
    }

    @Test fun signOutClearsTheSession() = runBlocking<Unit> {
        val vm = vm()
        server.on("DELETE", "/v1/me/devices/d1", FakeServer.empty())
        vm.signOut()
        val end = System.nanoTime() + 3_000_000_000
        while (app.sessions.load() != null && System.nanoTime() < end) Thread.sleep(10)
        assertNull(app.sessions.load())
        assertIs<Load.Content<*>>(vm.state.value.devices.let { it as? Load.Content<*> ?: Load.Content(Unit) })
    }
}
