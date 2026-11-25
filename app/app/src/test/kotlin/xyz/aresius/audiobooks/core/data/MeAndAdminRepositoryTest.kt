package xyz.aresius.audiobooks.core.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ErrorCode
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Me
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.SourceMode
import xyz.aresius.audiobooks.core.network.errorOrNull
import xyz.aresius.audiobooks.core.network.valueOrNull
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.TestApp

class MeAndAdminRepositoryTest {
    private val server = FakeServer()
    private val app = TestApp(server)

    @AfterTest fun tearDown() = server.close()

    @Test fun p2pAndSourceModeStayCoherent() {
        assertEquals(SourceMode.AUTO, P2pSettings.patch(true, SourceMode.DEBRID).sourceMode)
        assertEquals(SourceMode.AUTO, P2pSettings.patch(false, SourceMode.P2P).sourceMode)
        assertEquals(SourceMode.AUTO, P2pSettings.patch(true, SourceMode.AUTO).sourceMode)
        assertEquals(SourceMode.P2P, P2pSettings.patch(true, SourceMode.P2P).sourceMode)
        assertEquals(SourceMode.DEBRID, P2pSettings.patch(false, SourceMode.DEBRID).sourceMode)
        assertEquals(true, P2pSettings.patch(true, SourceMode.AUTO).p2pEnabled)
        assertEquals(false, P2pSettings.patch(false, SourceMode.AUTO).p2pEnabled)
    }

    @Test fun setP2pPatchesTheServerAndUpdatesTheSharedCopy() = runBlocking<Unit> {
        app.me.set(AudiobooksJson.decodeFromString(Me.serializer(), Samples.ME_USER)) // source_mode = debrid
        server.onJson("PATCH", "/v1/me", Samples.ME_ADMIN.replace(""""p2p_enabled":false""", """"p2p_enabled":true"""))
        app.me.setP2p(true)
        val body = server.lastBody("PATCH", "/v1/me")
        assertEquals("""{"settings":{"source_mode":"auto","p2p_enabled":true}}""", body)
        assertEquals(true, app.me.me.value?.settings?.p2pEnabled)
    }

    @Test fun theProfileIsCachedForOfflineStarts() = runBlocking<Unit> {
        server.onJson("GET", "/v1/me", Samples.ME_ADMIN)
        app.me.refresh()
        val second = MeRepository({ server.api }, app.cache) { app.now }
        assertNull(second.me.value)
        second.restore()
        assertEquals("Ada", second.me.value?.displayName)
        second.clear()
        assertNull(second.me.value)
    }

    @Test fun aRefreshThatWasOvertakenByAWriteDoesNotUndoIt() = runBlocking<Unit> {
        app.me.set(AudiobooksJson.decodeFromString(Me.serializer(), Samples.ME_ADMIN)) // region us
        val gotRequest = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server.onRequest("GET", "/v1/me") {
            gotRequest.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            FakeServer.json(Samples.ME_ADMIN) // what the server said BEFORE the PATCH
        }
        server.onJson("PATCH", "/v1/me", Samples.ME_ADMIN.replace(""""region":"us"""", """"region":"de""""))
        val refresh = async(Dispatchers.IO) { app.me.refresh() }
        assertTrue(gotRequest.await(5, java.util.concurrent.TimeUnit.SECONDS))
        app.me.updateSettings(xyz.aresius.audiobooks.core.network.SettingsPatch(region = "de"))
        release.countDown()
        refresh.await()
        assertEquals("de", app.me.me.value?.settings?.region, "the older GET answer must not replace the newer PATCH answer")
        val cached = MeRepository({ server.api }, app.cache) { app.now }.also { it.restore() }
        assertEquals("de", cached.me.value?.settings?.region, "nor the offline copy")
    }

    @Test fun refreshAfterSignOutDoesNotBringTheUserBack() = runBlocking<Unit> {
        app.me.set(AudiobooksJson.decodeFromString(Me.serializer(), Samples.ME_ADMIN))
        val gotRequest = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server.onRequest("GET", "/v1/me") {
            gotRequest.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            FakeServer.json(Samples.ME_ADMIN)
        }
        val refresh = async(Dispatchers.IO) { app.me.refresh() }
        assertTrue(gotRequest.await(5, java.util.concurrent.TimeUnit.SECONDS))
        app.me.clear()
        release.countDown()
        refresh.await()
        assertNull(app.me.me.value)
    }

    @Test fun failedUpdatesKeepTheOldProfile() = runBlocking<Unit> {
        app.me.set(AudiobooksJson.decodeFromString(Me.serializer(), Samples.ME_ADMIN))
        server.on("PATCH", "/v1/me", FakeServer.error(400, "invalid_request", "bad region"))
        val e = app.me.updateSettings(xyz.aresius.audiobooks.core.network.SettingsPatch(region = "xx")).errorOrNull
        assertIs<ApiError.Http>(e)
        assertEquals("us", app.me.me.value?.settings?.region)
    }

    @Test fun devicesAndLinkCodes() = runBlocking<Unit> {
        server.onJson("GET", "/v1/me/devices", """{"devices":[{"id":"d1","name":"Pixel","created_at":"2026-11-25T10:00:00Z","current":true}]}""")
        assertEquals("Pixel", app.me.devices().valueOrNull?.single()?.name)
        server.onJson("POST", "/v1/me/devices/link-code", """{"link_code":"AB12","expires_at":"2026-11-25T10:15:00Z"}""")
        assertEquals("AB12", app.me.createLinkCode().valueOrNull?.linkCode)
        server.on("DELETE", "/v1/me/devices/d9", FakeServer.error(404, "not_found"))
        assertEquals(ErrorCode.NotFound, (app.me.revokeDevice("d9").errorOrNull as ApiError.Http).code)
    }

    @Test fun debridKeysAreSentTrimmedAndRejectionIsTyped() = runBlocking<Unit> {
        server.onJson("PUT", "/v1/me/debrid/torbox", Samples.debrid("torbox"))
        assertTrue(app.debrid.put(Provider.TorBox, "  my-torbox-key \n").valueOrNull!!.isOk)
        assertEquals("""{"api_key":"my-torbox-key"}""", server.lastBody("PUT", "/v1/me/debrid/torbox"))
        server.on("PUT", "/v1/me/debrid/realdebrid", FakeServer.error(400, "bad_debrid_key", "rejected"))
        assertEquals(ErrorCode.BadDebridKey, (app.debrid.put(Provider.RealDebrid, "wrong-key-123").errorOrNull as ApiError.Http).code)
        server.onJson("POST", "/v1/me/debrid/torbox", Samples.debrid("torbox", status = "invalid"))
        assertEquals("invalid", app.debrid.recheck(Provider.TorBox).valueOrNull?.status)
        server.on("DELETE", "/v1/me/debrid/torbox", FakeServer.empty())
        assertEquals(Unit, app.debrid.delete(Provider.TorBox).valueOrNull)
        server.onJson("GET", "/v1/me/debrid", """{"providers":[${Samples.debrid("torbox", false)}]}""")
        assertEquals(false, app.debrid.list().valueOrNull?.single()?.configured)
    }

    @Test fun adminCallsAndTheOneTimeInviteCode() = runBlocking<Unit> {
        server.on("GET", "/v1/admin/users", FakeServer.error(403, "forbidden", "admin only"))
        assertEquals(ErrorCode.Forbidden, (app.admin.users().errorOrNull as ApiError.Http).code)
        server.onJson("PATCH", "/v1/admin/users/u2", """{"id":"u2","display_name":"Bob","role":"user","disabled":true,"created_at":"2026-11-25T10:00:00Z"}""")
        assertTrue(app.admin.setDisabled("u2", true).valueOrNull!!.disabled)
        assertEquals("""{"disabled":true}""", server.lastBody("PATCH", "/v1/admin/users/u2"))
        server.onJson("POST", "/v1/admin/invites", """{"id":"i1","created_at":"2026-11-25T10:00:00Z","expires_at":"2026-11-28T10:00:00Z","code":"ONE-TIME"}""")
        assertEquals("ONE-TIME", app.admin.createInvite("  ", 24).valueOrNull?.code)
        assertEquals("""{"expires_in_hours":24}""", server.lastBody("POST", "/v1/admin/invites"))
        server.onJson("GET", "/v1/admin/invites", """{"invites":[{"id":"i2","note":"x","created_at":"2026-11-25T10:00:00Z","expires_at":"2026-11-28T10:00:00Z"}]}""")
        assertEquals("i2", app.admin.invites().valueOrNull?.single()?.id)
        server.on("DELETE", "/v1/admin/invites/i2", FakeServer.empty())
        assertEquals(Unit, app.admin.revokeInvite("i2").valueOrNull)
    }
}
