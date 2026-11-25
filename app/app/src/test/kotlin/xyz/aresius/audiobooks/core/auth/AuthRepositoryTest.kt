package xyz.aresius.audiobooks.core.auth

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.aresius.audiobooks.core.network.SignOutReason
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.TestApp

class AuthRepositoryTest {
    private val server = FakeServer()
    private val app = TestApp(server, session = false)
    private val auth = app.auth()

    @AfterTest fun tearDown() = server.close()

    private fun <T> AuthResult<T>.ok(): T = (this as AuthResult.Success).value
    private fun AuthResult<*>.error(): AuthError = (this as AuthResult.Failure).error

    @Test fun bootstrapStoresTheSessionAndTheUser() = runBlocking<Unit> {
        server.onJson("POST", "/v1/auth/bootstrap", Samples.session())
        val me = auth.signIn(server.baseUrl + "/", SignInMethod.Bootstrap("  instance-pw \n", " Ada "), " Pixel 9 ").ok()
        assertEquals("Ada", me.displayName)
        val body = server.lastBody("POST", "/v1/auth/bootstrap")
        assertTrue(body.contains(""""instance_password":"instance-pw""""), body)
        assertTrue(body.contains(""""display_name":"Ada""""), body)
        assertTrue(body.contains(""""device_name":"Pixel 9""""), body)
        val session = app.sessions.load()
        assertNotNull(session)
        assertEquals(server.baseUrl, session.serverUrl)
        assertEquals("access-new", session.accessToken)
        assertEquals("u1", session.userId)
        assertEquals("Pixel 9", session.deviceName)
        assertEquals(app.now + 3_600_000, session.expiresAtMs)
        assertEquals("Ada", app.me.me.value?.displayName)
        assertEquals(server.baseUrl, app.prefs.serverUrl.first())
    }

    @Test fun joinAndLinkUseTheirOwnRoutes() = runBlocking<Unit> {
        server.onJson("POST", "/v1/auth/join", Samples.session(Samples.ME_USER))
        server.onJson("POST", "/v1/auth/link", Samples.session(Samples.ME_USER))
        auth.signIn(server.baseUrl, SignInMethod.Invite("INV-1", "Bob"), "Tablet").ok()
        assertTrue(server.lastBody("POST", "/v1/auth/join").contains(""""invite_code":"INV-1""""))
        auth.signIn(server.baseUrl, SignInMethod.Link("LINK-1"), "Tablet").ok()
        val link = server.lastBody("POST", "/v1/auth/link")
        assertTrue(link.contains(""""link_code":"LINK-1""""))
        assertTrue("display_name" !in link, "a link code signs in as the existing user: no name is sent")
    }

    @Test fun aWrongOrUsedCodeIsBadCredentials() = runBlocking<Unit> {
        server.on("POST", "/v1/auth/join", FakeServer.error(401, "unauthorized", "invite used"))
        assertEquals(AuthError.BadCredentials, auth.signIn(server.baseUrl, SignInMethod.Invite("x", "Bob"), "Tablet").error())
        assertNull(app.sessions.load())
    }

    @Test fun bootstrapOnAnInstanceWithUsersIsAConflict() = runBlocking<Unit> {
        server.on("POST", "/v1/auth/bootstrap", FakeServer.error(409, "conflict"))
        assertEquals(AuthError.AlreadySetUp, auth.signIn(server.baseUrl, SignInMethod.Bootstrap("pw", "Ada"), "Pixel").error())
    }

    @Test fun rateLimitCarriesTheWaitTime() = runBlocking<Unit> {
        server.on("POST", "/v1/auth/join", FakeServer.error(429, "rate_limited", null, "Retry-After" to "30"))
        assertEquals(AuthError.TooManyAttempts(30), auth.signIn(server.baseUrl, SignInMethod.Invite("x", "Bob"), "Tablet").error())
    }

    @Test fun invalidInputNeverReachesTheServer() = runBlocking<Unit> {
        val cases = listOf(
            Triple("", SignInMethod.Invite("x", "Bob"), "Tablet") to InputProblem.UrlEmpty,
            Triple(server.baseUrl, SignInMethod.Invite("  ", "Bob"), "Tablet") to InputProblem.SecretEmpty,
            Triple(server.baseUrl, SignInMethod.Invite("x", " "), "Tablet") to InputProblem.NameEmpty,
            Triple(server.baseUrl, SignInMethod.Invite("x", "Bob"), "") to InputProblem.DeviceNameEmpty,
            Triple(server.baseUrl, SignInMethod.Invite("x", "Bob"), "d".repeat(81)) to InputProblem.DeviceNameTooLong,
        )
        cases.forEach { (args, problem) ->
            assertEquals(AuthError.InvalidInput(problem), auth.signIn(args.first, args.second, args.third).error())
        }
        assertTrue(server.requests.isEmpty())
        val strict = app.auth(allowCleartext = false)
        assertEquals(AuthError.InvalidInput(InputProblem.UrlNeedsHttps), strict.signIn("http://audiobooks.example.org", SignInMethod.Link("x"), "Tablet").error())
    }

    @Test fun anUnreachableServerIsANetworkError() = runBlocking<Unit> {
        val url = server.baseUrl
        server.close()
        assertEquals(AuthError.Network, auth.signIn(url, SignInMethod.Invite("x", "Bob"), "Tablet").error())
    }

    @Test fun checkServerReadsTheVersionRoute() = runBlocking<Unit> {
        server.onJson("GET", "/v1/version", """{"version":"0.4.0","api_version":1,"bootstrapped":false}""")
        val v = auth.checkServer(server.baseUrl).ok()
        assertEquals(false, v.bootstrapped)
        server.onJson("GET", "/v1/version", """{"hello":"not us"}""")
        assertIs<AuthError.Unknown>(auth.checkServer(server.baseUrl).error())
        server.on("GET", "/v1/version", FakeServer.error(404, "not_found"))
        assertIs<AuthError.Server>(auth.checkServer(server.baseUrl).error())
    }

    @Test fun signOutRevokesThisDeviceThenForgetsTheSession() = runBlocking<Unit> {
        server.onJson("POST", "/v1/auth/join", Samples.session(Samples.ME_USER))
        auth.signIn(server.baseUrl, SignInMethod.Invite("x", "Bob"), "Tablet").ok()
        server.onJson("GET", "/v1/me/devices", """{"devices":[{"id":"d0","name":"Old","created_at":"2026-11-01T10:00:00Z","current":false},{"id":"d1","name":"Tablet","created_at":"2026-11-25T10:00:00Z","current":true}]}""")
        server.on("DELETE", "/v1/me/devices/d1", FakeServer.empty())
        val event = async(start = CoroutineStart.UNDISPATCHED) { app.signedOut.events.first() }
        auth.signOut()
        assertEquals(1, server.requestsTo("DELETE", "/v1/me/devices/d1").size)
        assertEquals(0, server.requestsTo("DELETE", "/v1/me/devices/d0").size)
        assertNull(app.sessions.load())
        assertEquals(SignOutReason.UserRequested, withTimeout(3000) { event.await() })
    }

    @Test fun signOutWorksWhenTheServerIsUnreachable() = runBlocking<Unit> {
        server.onJson("POST", "/v1/auth/join", Samples.session(Samples.ME_USER))
        auth.signIn(server.baseUrl, SignInMethod.Invite("x", "Bob"), "Tablet").ok()
        server.close()
        auth.signOut()
        assertNull(app.sessions.load())
    }
}
