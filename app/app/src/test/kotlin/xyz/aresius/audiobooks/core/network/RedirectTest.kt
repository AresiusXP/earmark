package xyz.aresius.audiobooks.core.network

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.memorySessionStore
import xyz.aresius.audiobooks.testutil.testSession

/** A redirect on an API or auth call is an error: the body (token, password, code) must never reach the other host. */
class RedirectTest {
    private val origin = FakeServer()
    private val other = FakeServer()
    private val sessions = memorySessionStore(testSession(origin.baseUrl))
    private val factory = HttpClientFactory(sessions, SignedOutEvents(), NetworkTimeouts(), emptyList())
    private val provider = ApiProvider(sessions, factory)

    @AfterTest fun tearDown() {
        origin.close()
        other.close()
    }

    private fun redirect(code: Int) = MockResponse.Builder().code(code).addHeader("Location", other.baseUrl + "/stolen").build()

    private fun assertNothingReachedTheOtherHost() {
        assertEquals(emptyList(), other.requests.map { it.method + " " + it.url.encodedPath })
    }

    @Test fun unauthenticatedPostsAreNeverRedirected() = runBlocking<Unit> {
        for (code in listOf(301, 302, 303, 307, 308)) {
            origin.on("POST", "/v1/auth/bootstrap", redirect(code))
            origin.on("POST", "/v1/auth/join", redirect(code))
            origin.on("POST", "/v1/auth/link", redirect(code))
            origin.on("POST", "/v1/auth/google", redirect(code))
            val api = provider.unauthenticated(origin.baseUrl)
            assertNotNull(safeApiCall { api.bootstrap(BootstrapRequest("hunter2", "Ada", "Pixel")) }.errorOrNull, "bootstrap $code")
            assertNotNull(safeApiCall { api.join(JoinRequest("INVITE", "Bob", "Pixel")) }.errorOrNull, "join $code")
            assertNotNull(safeApiCall { api.linkDevice(LinkRequest("LINKCODE", "Pixel")) }.errorOrNull, "link $code")
            assertNotNull(safeApiCall { api.googleSignIn(GoogleSignInRequest("id-token", "Pixel")) }.errorOrNull, "google $code")
        }
        assertNothingReachedTheOtherHost()
    }

    @Test fun authenticatedCallsAreNeverRedirected() = runBlocking<Unit> {
        origin.on("PATCH", "/v1/me", redirect(308))
        origin.on("POST", "/v1/me/google", redirect(307))
        val api = provider.api()
        assertNotNull(safeApiCall { api.updateMe(UpdateMeRequest(displayName = "x")) }.errorOrNull)
        assertNotNull(safeApiCall { api.linkGoogle(GoogleLinkRequest("id-token")) }.errorOrNull)
        assertNothingReachedTheOtherHost()
    }

    @Test fun theRefreshCallDoesNotFollowARedirectEither() = runBlocking<Unit> {
        origin.onJson("GET", "/v1/me", """{"error":{"code":"unauthorized"}}""", code = 401)
        origin.on("POST", "/v1/auth/refresh", redirect(307))
        assertTrue(safeApiCall { provider.api().getMe() }.errorOrNull!!.isUnauthorized)
        assertEquals(1, origin.requestsTo("POST", "/v1/auth/refresh").size)
        assertNothingReachedTheOtherHost()
        assertEquals("refresh-1", sessions.load()?.refreshToken, "a redirected refresh changes nothing")
    }

    @Test fun everyClientThatCarriesApiOrAuthTrafficKeepsRedirectsOff() {
        for (client in listOf(factory.api(), factory.plain())) {
            assertTrue(!client.followRedirects && !client.followSslRedirects)
        }
        assertTrue(factory.cdn().followRedirects)
    }

    @Test fun theCredentialFreeCdnClientStillFollowsRedirectsWithoutAToken() {
        other.on("GET", "/file", FakeServer.json("{}"))
        origin.on("GET", "/cdn", redirect(302).newBuilder().setHeader("Location", other.baseUrl + "/file").build())
        factory.cdn().newCall(Request.Builder().url(origin.baseUrl + "/cdn").build()).execute().use { assertEquals(200, it.code) }
        assertEquals(1, other.requests.size)
        assertTrue(other.requests.single().headers["Authorization"] == null)
    }

    @Test fun aPostBodyThroughThePlainClientIsNotForwarded() {
        origin.on("POST", "/x", redirect(307))
        val body = """{"refresh_token":"secret"}""".toRequestBody("application/json".toMediaType())
        factory.plain().newCall(Request.Builder().url(origin.baseUrl + "/x").post(body).build()).execute().use { assertEquals(307, it.code) }
        assertNothingReachedTheOtherHost()
    }
}
