package xyz.aresius.audiobooks.feature.settings

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import xyz.aresius.audiobooks.core.auth.AuthError
import xyz.aresius.audiobooks.core.auth.GoogleTokenResult
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Me
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.MainDispatcherRule
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.ScriptedGoogle
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.UnusedTokenSource
import xyz.aresius.audiobooks.testutil.awaitState
import xyz.aresius.audiobooks.testutil.googleAuth

class GoogleAccountViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val server = FakeServer()
    private val app = TestApp(server, session = true)
    private val linked = Samples.ME_ADMIN.dropLast(1) + ""","google":{"linked":true,"email":"grace@example.test"}}"""

    @AfterTest fun tearDown() = server.close()

    private fun version(enabled: Boolean?) = server.onJson(
        "GET", "/v1/version",
        """{"version":"1","api_version":1,"bootstrapped":true${if (enabled != null) ""","google_enabled":$enabled""" else ""}}""",
    )

    private fun nonce() = server.onJson("GET", "/v1/auth/google/nonce", """{"nonce":"n1","expires_at":"2026-11-25T10:05:00Z"}""")

    private suspend fun vm(me: String = Samples.ME_ADMIN, enabled: Boolean? = true): GoogleAccountViewModel {
        version(enabled)
        app.me.set(AudiobooksJson.decodeFromString(Me.serializer(), me))
        return GoogleAccountViewModel(app.googleAuth(), app.me, UnusedTokenSource())
    }

    @Test fun theCardIsHiddenWhenGoogleIsOffOnTheServerAndNothingIsLinked() = runBlocking<Unit> {
        val vm = vm(enabled = false)
        assertTrue(vm.state.awaitState { it.offered != null }.hidden)
        assertTrue(vm(enabled = null).state.awaitState { it.offered != null }.hidden, "an older server")
    }

    @Test fun aLinkedAccountStaysVisibleEvenWhenTheServerSwitchedGoogleOff() = runBlocking<Unit> {
        val s = vm(linked, enabled = false).state.awaitState { it.offered != null }
        assertFalse(s.hidden)
        assertEquals("grace@example.test", s.google.email)
    }

    @Test fun linkingShowsTheLinkedEmail() = runBlocking<Unit> {
        val vm = vm()
        vm.state.awaitState { it.offered == true }
        nonce()
        server.onJson("POST", "/v1/me/google", linked)
        vm.link(ScriptedGoogle())
        val s = vm.state.awaitState { it.google.linked }
        assertEquals("grace@example.test", s.google.email)
        assertFalse(s.busy)
        assertNull(s.error)
    }

    @Test fun anIdentityOfSomeoneElseIsAConflictAndNothingIsLinked() = runBlocking<Unit> {
        val vm = vm()
        nonce()
        server.on("POST", "/v1/me/google", FakeServer.error(409, "conflict"))
        vm.link(ScriptedGoogle())
        val s = vm.state.awaitState { it.error != null }
        assertEquals(AuthError.GoogleConflict, s.error)
        assertFalse(s.google.linked)
        vm.consumeError()
        assertNull(vm.state.value.error)
    }

    @Test fun cancellingOrHavingNoAccountLeavesThingsAsTheyWereOrExplains() = runBlocking<Unit> {
        val vm = vm()
        nonce()
        vm.link(ScriptedGoogle(ScriptedGoogle.cancelled()))
        val s = vm.state.awaitState { !it.busy }
        assertNull(s.error)
        vm.link(ScriptedGoogle(ScriptedGoogle.result(GoogleTokenResult.NoAccount)))
        assertEquals(AuthError.GoogleNoAccount, vm.state.awaitState { it.error != null }.error)
        assertEquals(0, server.requestsTo("POST", "/v1/me/google").size)
    }

    @Test fun unlinkingDropsTheLinkAndAFailureIsShown() = runBlocking<Unit> {
        val vm = vm(linked)
        server.on("DELETE", "/v1/me/google", FakeServer.error(500, "upstream_failed"))
        vm.unlink()
        assertIs<ApiError.Http>(vm.state.awaitState { it.apiError != null }.apiError)
        assertTrue(vm.state.value.google.linked)
        server.on("DELETE", "/v1/me/google", FakeServer.empty())
        vm.unlink()
        val s = vm.state.awaitState { !it.google.linked }
        assertNull(s.apiError)
        assertNull(s.google.email)
    }
}
