package xyz.aresius.audiobooks.core.auth

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.GoogleJoinRequest
import xyz.aresius.audiobooks.core.network.GoogleLinkRequest
import xyz.aresius.audiobooks.core.network.GoogleSignInRequest
import xyz.aresius.audiobooks.core.network.Me
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.ScriptedGoogle
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.TestCodes
import xyz.aresius.audiobooks.testutil.googleAuth
import xyz.aresius.audiobooks.testutil.testSession

class GoogleAuthRepositoryTest {
    private val server = FakeServer()
    private val app = TestApp(server, session = false)
    private val google = app.googleAuth()

    @AfterTest fun tearDown() = server.close()

    private val nonce = "/v1/auth/google/nonce"
    private val signIn = "/v1/auth/google"
    private val join = "/v1/auth/google/join"

    private fun nonces(vararg values: String) = server.on("GET", nonce, *values.map { FakeServer.json("""{"nonce":"$it","expires_at":"2026-11-25T10:05:00Z"}""") }.toTypedArray())

    private fun GoogleOutcome.failure(): AuthError = (this as GoogleOutcome.Failed).error

    @Test fun signInFetchesANonceAsksGoogleWithItAndPostsTheToken() = runBlocking<Unit> {
        nonces("n1")
        server.onJson("POST", signIn, Samples.session(Samples.ME_USER))
        val script = ScriptedGoogle()
        val out = google.signIn(server.baseUrl, " Pixel 9 ", script)
        assertEquals("Bob", assertIs<GoogleOutcome.Done>(out).me.displayName)
        assertEquals(listOf("n1"), script.nonces)
        val body = server.lastBody("POST", signIn)
        assertTrue(body.contains(""""id_token":"id-token-for-n1""""), "the ID token that carries the nonce is sent")
        assertTrue(body.contains(""""device_name":"Pixel 9""""))
        assertNotNull(app.sessions.load())
        assertEquals("u2", app.sessions.load()?.userId)
        assertEquals("Bob", app.me.me.value?.displayName)
    }

    @Test fun aFailedAttemptIsFollowedByAFreshNonceAndAFreshIdToken() = runBlocking<Unit> {
        nonces("n1", "n2")
        server.on("POST", signIn, FakeServer.error(401, "unauthorized", "invalid token"), FakeServer.json(Samples.session(Samples.ME_USER)))
        val script = ScriptedGoogle()
        assertEquals(AuthError.GoogleRejected, google.signIn(server.baseUrl, "Pixel", script).failure())
        assertNull(app.sessions.load())
        assertIs<GoogleOutcome.Done>(google.signIn(server.baseUrl, "Pixel", script))
        assertEquals(listOf("n1", "n2"), script.nonces, "the retry asked Google again, for the new nonce")
        assertEquals(2, server.requestsTo("GET", nonce).size)
        val bodies = server.requestsTo("POST", signIn).map { it.body?.utf8().orEmpty() }
        assertTrue(bodies[0].contains("id-token-for-n1") && bodies[1].contains("id-token-for-n2"), "never the old token again")
        assertFalse(bodies[1].contains("n1"))
    }

    @Test fun anUnknownGoogleAccountIsNotLinkedAndCarriesTheGivenNameForTheJoinForm() = runBlocking<Unit> {
        nonces("n1")
        server.on("POST", signIn, FakeServer.error(401, "google_not_linked", "not linked"))
        val out = google.signIn(server.baseUrl, "Pixel", ScriptedGoogle(ScriptedGoogle.token(given = "Grace")))
        assertEquals(GoogleOutcome.NotLinked("Grace"), out)
        assertNull(app.sessions.load())
    }

    @Test fun notLinkedThenJoinUsesAFreshNonceAndAFreshTokenAndTheNormalisedInvite() = runBlocking<Unit> {
        nonces("n1", "n2")
        server.on("POST", signIn, FakeServer.error(401, "google_not_linked"))
        server.onJson("POST", join, Samples.session(Samples.ME_USER))
        val script = ScriptedGoogle()
        assertIs<GoogleOutcome.NotLinked>(google.signIn(server.baseUrl, "Pixel", script))
        val typed = TestCodes.INVITE.lowercase().chunked(5).joinToString(" ")
        assertIs<GoogleOutcome.Done>(google.join(server.baseUrl, typed, " Grace ", "Pixel", script))
        assertEquals(listOf("n1", "n2"), script.nonces)
        val body = server.lastBody("POST", join)
        assertTrue(body.contains(""""id_token":"id-token-for-n2""""), body)
        assertTrue(body.contains(""""invite_code":"${TestCodes.INVITE}""""), body)
        assertTrue(body.contains(""""display_name":"Grace""""), body)
        assertTrue(body.contains(""""device_name":"Pixel""""), body)
        assertNotNull(app.sessions.load())
    }

    @Test fun joinWithAnUnknownInviteIsBadCredentialsAndTheNextTryStartsOverAtTheNonce() = runBlocking<Unit> {
        nonces("n1", "n2")
        server.on("POST", join, FakeServer.error(401, "unauthorized"), FakeServer.json(Samples.session(Samples.ME_USER)))
        val script = ScriptedGoogle()
        assertEquals(AuthError.BadCredentials, google.join(server.baseUrl, TestCodes.OTHER, "Grace", "Pixel", script).failure())
        assertIs<GoogleOutcome.Done>(google.join(server.baseUrl, TestCodes.INVITE, "Grace", "Pixel", script))
        assertEquals(listOf("n1", "n2"), script.nonces)
        val tokens = server.requestsTo("POST", join).map { it.body?.utf8().orEmpty() }
        assertTrue("id-token-for-n1" in tokens[0] && "id-token-for-n2" in tokens[1], "the second attempt carries the second token")
    }

    @Test fun anIdentityLinkedToSomeoneElseIsAConflictOnJoin() = runBlocking<Unit> {
        nonces("n1")
        server.on("POST", join, FakeServer.error(409, "conflict", "identity taken"))
        assertEquals(AuthError.GoogleConflict, google.join(server.baseUrl, TestCodes.INVITE, "Grace", "Pixel", ScriptedGoogle()).failure())
        assertNull(app.sessions.load())
    }

    @Test fun cancellingTheSheetIsSilentAndSendsNothing() = runBlocking<Unit> {
        nonces("n1")
        assertEquals(GoogleOutcome.Cancelled, google.signIn(server.baseUrl, "Pixel", ScriptedGoogle(ScriptedGoogle.cancelled())))
        assertEquals(0, server.requestsTo("POST", signIn).size)
    }

    @Test fun noAccountNoPlayServicesAndABrokenSheetAreExplained() = runBlocking<Unit> {
        nonces("n1")
        assertEquals(AuthError.GoogleNoAccount, google.signIn(server.baseUrl, "Pixel", ScriptedGoogle(ScriptedGoogle.result(GoogleTokenResult.NoAccount))).failure())
        assertEquals(AuthError.GoogleUnavailable, google.signIn(server.baseUrl, "Pixel", ScriptedGoogle(ScriptedGoogle.result(GoogleTokenResult.Unavailable))).failure())
        assertEquals(AuthError.GoogleFailed, google.signIn(server.baseUrl, "Pixel", ScriptedGoogle(ScriptedGoogle.result(GoogleTokenResult.Failed("x")))).failure())
        val throwing = ScriptedGoogle({ error("boom id-token-secret") })
        assertEquals(AuthError.GoogleFailed, google.signIn(server.baseUrl, "Pixel", throwing).failure())
        assertEquals(0, server.requestsTo("POST", signIn).size)
    }

    @Test fun aServerWithoutGoogleAnswersDisabledAtTheNonce() = runBlocking<Unit> {
        server.on("GET", nonce, FakeServer.error(404, "google_disabled"))
        val script = ScriptedGoogle()
        assertEquals(AuthError.GoogleDisabled, google.signIn(server.baseUrl, "Pixel", script).failure())
        assertTrue(script.nonces.isEmpty(), "Google is never asked when the server cannot verify the token")
    }

    @Test fun networkRateLimitAndUpstreamErrorsMapToTheirOwnErrors() = runBlocking<Unit> {
        nonces("n1")
        server.on("POST", signIn, FakeServer.error(429, "rate_limited", null, "Retry-After" to "12"))
        assertEquals(AuthError.TooManyAttempts(12), google.signIn(server.baseUrl, "Pixel", ScriptedGoogle()).failure())
        server.on("POST", signIn, FakeServer.error(400, "invalid_request", "bad body"))
        assertEquals(AuthError.Server(400, "bad body"), google.signIn(server.baseUrl, "Pixel", ScriptedGoogle()).failure())
        val url = server.baseUrl
        server.close()
        assertEquals(AuthError.Network, google.signIn(url, "Pixel", ScriptedGoogle()).failure())
    }

    @Test fun badInputNeverReachesGoogleOrTheServer() = runBlocking<Unit> {
        val script = ScriptedGoogle()
        assertEquals(AuthError.InvalidInput(InputProblem.UrlEmpty), google.signIn("", "Pixel", script).failure())
        assertEquals(AuthError.InvalidInput(InputProblem.DeviceNameEmpty), google.signIn(server.baseUrl, " ", script).failure())
        assertEquals(AuthError.InvalidInput(InputProblem.SecretEmpty), google.join(server.baseUrl, " ", "Grace", "Pixel", script).failure())
        assertEquals(AuthError.InvalidInput(InputProblem.CodeInvalid), google.join(server.baseUrl, "short", "Grace", "Pixel", script).failure())
        assertEquals(AuthError.InvalidInput(InputProblem.NameEmpty), google.join(server.baseUrl, TestCodes.INVITE, " ", "Pixel", script).failure())
        assertTrue(script.nonces.isEmpty())
        assertTrue(server.requests.isEmpty())
    }

    // --- link / unlink (signed in) ---------------------------------------------------------------------------------

    private suspend fun signedIn(me: String = Samples.ME_ADMIN) {
        app.sessions.save(testSession(server.baseUrl))
        app.me.set(AudiobooksJson.decodeFromString(Me.serializer(), me))
    }

    private val linkedMe = Samples.ME_ADMIN.dropLast(1) + ""","google":{"linked":true,"email":"grace@example.test"}}"""

    @Test fun linkSendsTheTokenAndStoresTheUpdatedUser() = runBlocking<Unit> {
        signedIn()
        nonces("n1")
        server.onJson("POST", "/v1/me/google", linkedMe)
        val out = google.link(ScriptedGoogle())
        assertEquals("grace@example.test", assertIs<GoogleOutcome.Done>(out).me.google.email)
        assertTrue(server.lastBody("POST", "/v1/me/google").contains(""""id_token":"id-token-for-n1""""))
        assertEquals(true, app.me.me.value?.google?.linked)
        assertEquals("Bearer access-1", server.requestsTo("POST", "/v1/me/google").single().headers["Authorization"])
    }

    @Test fun linkConflictAndRejectionAndCancel() = runBlocking<Unit> {
        signedIn()
        nonces("n1")
        server.on("POST", "/v1/me/google", FakeServer.error(409, "conflict"))
        assertEquals(AuthError.GoogleConflict, google.link(ScriptedGoogle()).failure())
        assertEquals(false, app.me.me.value?.google?.linked)
        assertEquals(GoogleOutcome.Cancelled, google.link(ScriptedGoogle(ScriptedGoogle.cancelled())))
        server.on("POST", "/v1/me/google", FakeServer.error(400, "invalid_request", "bad token"))
        assertEquals(AuthError.Server(400, "bad token"), google.link(ScriptedGoogle()).failure())
    }

    @Test fun linkNeedsASession() = runBlocking<Unit> {
        assertEquals(AuthError.BadCredentials, google.link(ScriptedGoogle()).failure())
    }

    @Test fun unlinkClearsTheLinkLocallyAndIsIdempotent() = runBlocking<Unit> {
        signedIn(linkedMe)
        server.on("DELETE", "/v1/me/google", FakeServer.empty())
        assertIs<ApiResult.Success<Unit>>(google.unlink())
        assertEquals(false, app.me.me.value?.google?.linked)
        assertNull(app.me.me.value?.google?.email)
        assertIs<ApiResult.Success<Unit>>(google.unlink())
        server.on("DELETE", "/v1/me/google", FakeServer.error(404, "google_disabled"))
        assertIs<ApiResult.Failure>(google.unlink())
    }

    @Test fun theServerOffersGoogleOnlyWhenTheVersionSaysSo() = runBlocking<Unit> {
        signedIn()
        server.onJson("GET", "/v1/version", """{"version":"1","api_version":1,"bootstrapped":true,"google_enabled":true}""")
        assertTrue(google.serverOffersGoogle())
        server.onJson("GET", "/v1/version", """{"version":"1","api_version":1,"bootstrapped":true,"google_enabled":false}""")
        assertFalse(google.serverOffersGoogle())
        server.onJson("GET", "/v1/version", """{"version":"1","api_version":1,"bootstrapped":true}""")
        assertFalse(google.serverOffersGoogle(), "an older server without the field")
        server.on("GET", "/v1/version", FakeServer.error(500, "upstream_failed"))
        assertFalse(google.serverOffersGoogle())
    }

    @Test fun anIdTokenNeverShowsInToStringOrOutcomes() {
        val credential = GoogleCredential("super-secret-token", "Grace", "g@example.test")
        assertFalse("super-secret-token" in credential.toString())
        assertFalse("super-secret-token" in GoogleTokenResult.Token(credential).toString())
        assertFalse("super-secret-token" in GoogleSignInRequest("super-secret-token", "Pixel").toString())
        assertFalse("super-secret-token" in GoogleJoinRequest("super-secret-token", "code", null, "Pixel").toString())
        assertFalse("super-secret-token" in GoogleLinkRequest("super-secret-token").toString())
    }
}
