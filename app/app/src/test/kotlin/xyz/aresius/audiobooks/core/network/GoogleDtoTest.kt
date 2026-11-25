package xyz.aresius.audiobooks.core.network

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.TestApp

class GoogleDtoTest {
    private val server = FakeServer()
    private val app = TestApp(server)

    @AfterTest fun tearDown() = server.close()

    private fun encode(patch: SettingsPatch) = AudiobooksJson.encodeToString(SettingsPatch.serializer(), patch)

    @Test fun absentMeansUnchangedAndClearIsAnExplicitNull() {
        assertEquals("{}", encode(SettingsPatch()))
        assertEquals("""{"preferred_provider":null}""", encode(SettingsPatch(preferredProvider = ProviderChoice.Clear)))
        assertEquals("""{"preferred_provider":"torbox"}""", encode(SettingsPatch(preferredProvider = ProviderChoice.Set(Provider.TorBox))))
        assertEquals(
            """{"region":"nl","p2p_enabled":false,"preferred_provider":null}""",
            encode(SettingsPatch(region = "nl", p2pEnabled = false, preferredProvider = ProviderChoice.Clear)),
        )
    }

    @Test fun clearingThePreferredProviderReachesTheServerAsNull() = runBlocking<Unit> {
        server.onJson("PATCH", "/v1/me", Samples.ME_ADMIN)
        app.me.updateSettings(SettingsPatch(preferredProvider = ProviderChoice.Clear))
        val body = server.lastBody("PATCH", "/v1/me")
        assertEquals("""{"settings":{"preferred_provider":null}}""", body)
        app.me.updateSettings(SettingsPatch(sourceMode = SourceMode.AUTO))
        assertFalse("preferred_provider" in server.lastBody("PATCH", "/v1/me"), "other patches leave the field out")
    }

    @Test fun theChoiceRoundTrips() {
        val json = AudiobooksJson
        assertEquals(ProviderChoice.Clear, json.decodeFromString(ProviderChoiceSerializer, "null"))
        assertEquals(ProviderChoice.Set(Provider.RealDebrid), json.decodeFromString(ProviderChoiceSerializer, "\"realdebrid\""))
    }

    @Test fun meCarriesTheGoogleLinkAndOlderCachesStillParse() {
        val me = AudiobooksJson.decodeFromString(Me.serializer(), Samples.ME_ADMIN.dropLast(1) + ""","google":{"linked":true,"email":"g@example.test"}}""")
        assertTrue(me.google.linked)
        assertEquals("g@example.test", me.google.email)
        val old = AudiobooksJson.decodeFromString(Me.serializer(), Samples.ME_ADMIN)
        assertFalse(old.google.linked)
        assertEquals(null, old.google.email)
        val unlinked = AudiobooksJson.decodeFromString(Me.serializer(), Samples.ME_ADMIN.dropLast(1) + ""","google":{"linked":false}}""")
        assertFalse(unlinked.google.linked)
    }

    @Test fun versionAndNonceAndAdminUserParse() {
        val v = AudiobooksJson.decodeFromString(VersionInfo.serializer(), """{"version":"1","api_version":1,"bootstrapped":true,"google_enabled":true}""")
        assertTrue(v.googleEnabled)
        assertFalse(AudiobooksJson.decodeFromString(VersionInfo.serializer(), """{"version":"1","api_version":1,"bootstrapped":true}""").googleEnabled)
        assertEquals("abc", AudiobooksJson.decodeFromString(GoogleNonceResponse.serializer(), """{"nonce":"abc","expires_at":"2026-11-25T10:05:00Z"}""").nonce)
        val u = AudiobooksJson.decodeFromString(AdminUser.serializer(), """{"id":"u","display_name":"A","role":"user","disabled":false,"created_at":"x","debrid":{},"google_linked":true,"google_email":"a@b.test"}""")
        assertTrue(u.googleLinked)
        assertEquals("a@b.test", u.googleEmail)
    }

    @Test fun googleErrorCodesAreKnown() {
        assertEquals(ErrorCode.GoogleNotLinked, apiErrorFrom(401, """{"error":{"code":"google_not_linked"}}""", null).code)
        assertEquals(ErrorCode.GoogleDisabled, apiErrorFrom(404, """{"error":{"code":"google_disabled"}}""", null).code)
    }

    @Test fun googleRequestsUseTheContractFieldNames() {
        assertEquals("""{"id_token":"t","device_name":"d"}""", AudiobooksJson.encodeToString(GoogleSignInRequest.serializer(), GoogleSignInRequest("t", "d")))
        assertEquals(
            """{"id_token":"t","invite_code":"c","display_name":"N","device_name":"d"}""",
            AudiobooksJson.encodeToString(GoogleJoinRequest.serializer(), GoogleJoinRequest("t", "c", "N", "d")),
        )
        assertEquals("""{"id_token":"t"}""", AudiobooksJson.encodeToString(GoogleLinkRequest.serializer(), GoogleLinkRequest("t")))
    }
}
