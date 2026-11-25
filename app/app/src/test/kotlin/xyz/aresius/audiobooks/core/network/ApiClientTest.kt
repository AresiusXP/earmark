package xyz.aresius.audiobooks.core.network

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.memorySessionStore
import xyz.aresius.audiobooks.testutil.testSession

class ApiClientTest {
    private val server = FakeServer()
    private val signedOut = SignedOutEvents()
    private val sessions = memorySessionStore(testSession(server.baseUrl))
    private val factory = HttpClientFactory(sessions, signedOut, NetworkTimeouts(), emptyList())
    private val provider = ApiProvider(sessions, factory)

    @AfterTest fun tearDown() = server.close()

    private suspend fun me() = safeApiCall { provider.api().getMe() }
    private fun retryAfterZero() = arrayOf("Retry-After" to "0")

    @Test fun retriesGatewayErrorsAndHonoursRetryAfterZero() = runBlocking<Unit> {
        server.on(
            "GET", "/v1/me",
            FakeServer.error(503, "upstream_failed", "restarting", *retryAfterZero()),
            FakeServer.error(502, "upstream_failed", null, *retryAfterZero()),
            FakeServer.json(Samples.ME_ADMIN),
        )
        val r = me()
        assertEquals("Ada", r.valueOrNull?.displayName)
        assertEquals(3, server.requestsTo("GET", "/v1/me").size)
    }

    @Test fun givesUpAfterThreeAttemptsWithTheTypedError() = runBlocking<Unit> {
        server.on("GET", "/v1/me", FakeServer.error(504, "upstream_failed", "slow", *retryAfterZero()))
        val e = me().errorOrNull
        assertIs<ApiError.Http>(e)
        assertEquals(504, e.status)
        assertEquals("slow", e.message)
        assertEquals(3, server.requestsTo("GET", "/v1/me").size)
    }

    @Test fun postsAreRetriedOnGatewayStatusToo() = runBlocking<Unit> {
        server.on(
            "POST", "/v1/me/devices/link-code",
            FakeServer.error(503, "upstream_failed", null, *retryAfterZero()),
            FakeServer.json("""{"link_code":"ABCD-1234","expires_at":"2026-11-25T10:15:00Z"}"""),
        )
        assertEquals("ABCD-1234", safeApiCall { provider.api().createLinkCode() }.valueOrNull?.linkCode)
        assertEquals(2, server.requestsTo("POST", "/v1/me/devices/link-code").size)
    }

    @Test fun otherServerErrorsAreNotRetried() = runBlocking<Unit> {
        server.on("GET", "/v1/me", FakeServer.error(500, "upstream_failed", "boom"))
        assertEquals(500, (me().errorOrNull as ApiError.Http).status)
        assertEquals(1, server.requestsTo("GET", "/v1/me").size)
    }

    private fun flakyClient(failures: Int, counter: java.util.concurrent.atomic.AtomicInteger): okhttp3.OkHttpClient =
        okhttp3.OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(sleeper = {}))
            .addInterceptor(okhttp3.Interceptor { chain ->
                if (counter.getAndIncrement() < failures) throw java.io.IOException("connection reset") else chain.proceed(chain.request())
            })
            .build()

    @Test fun anIoFailureIsRetriedForGet() {
        server.on("GET", "/x", FakeServer.json("{}"))
        val calls = java.util.concurrent.atomic.AtomicInteger()
        flakyClient(1, calls).newCall(Request.Builder().url(server.baseUrl + "/x").build()).execute().use { assertEquals(200, it.code) }
        assertEquals(2, calls.get())
    }

    @Test fun anIoFailureIsNotRetriedForPost() {
        server.on("POST", "/x", FakeServer.json("{}"))
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val body = "{}".toRequestBody()
        assertFailsWith<java.io.IOException> { flakyClient(1, calls).newCall(Request.Builder().url(server.baseUrl + "/x").post(body).build()).execute() }
        assertEquals(1, calls.get())
    }

    @Test fun sendsTheBearerTokenOfTheSession() = runBlocking<Unit> {
        server.onJson("GET", "/v1/me", Samples.ME_ADMIN)
        me()
        assertEquals("Bearer access-1", server.requests.single().headers["Authorization"])
    }

    @Test fun a401RefreshesOnceAndRetriesWithTheNewToken() = runBlocking<Unit> {
        server.onRequest("GET", "/v1/me") { req ->
            if (req.headers["Authorization"] == "Bearer access-1") FakeServer.error(401, "unauthorized") else FakeServer.json(Samples.ME_ADMIN)
        }
        server.onJson("POST", "/v1/auth/refresh", Samples.session())
        val r = me()
        assertEquals("Ada", r.valueOrNull?.displayName)
        assertEquals(1, server.requestsTo("POST", "/v1/auth/refresh").size)
        assertTrue(server.lastBody("POST", "/v1/auth/refresh").contains(""""refresh_token":"refresh-1""""))
        assertNull(server.requestsTo("POST", "/v1/auth/refresh").single().headers["Authorization"])
        assertEquals("Bearer access-new", server.requestsTo("GET", "/v1/me").last().headers["Authorization"])
        val stored = sessions.load()
        assertEquals("access-new", stored?.accessToken)
        assertEquals("refresh-new", stored?.refreshToken)
        assertEquals("u1", stored?.userId)
    }

    @Test fun aRejectedRefreshSignsOut() = runBlocking<Unit> {
        server.onJson("GET", "/v1/me", """{"error":{"code":"unauthorized"}}""", code = 401)
        server.onJson("POST", "/v1/auth/refresh", """{"error":{"code":"unauthorized"}}""", code = 401)
        val event = async(start = CoroutineStart.UNDISPATCHED) { signedOut.events.first() }
        val e = me().errorOrNull
        assertTrue(e!!.isUnauthorized)
        assertEquals(SignOutReason.RefreshRejected, withTimeout(3000) { event.await() })
        assertNull(sessions.load())
    }

    @Test fun refreshIsTriedOnlyOncePerRequest() = runBlocking<Unit> {
        server.onJson("GET", "/v1/me", """{"error":{"code":"unauthorized"}}""", code = 401)
        server.onJson("POST", "/v1/auth/refresh", Samples.session())
        val e = me().errorOrNull
        assertTrue(e!!.isUnauthorized)
        assertEquals(1, server.requestsTo("POST", "/v1/auth/refresh").size)
        assertEquals(2, server.requestsTo("GET", "/v1/me").size)
        assertNotNull(sessions.load()) // the session survives: only a rejected refresh token signs out
    }

    @Test fun aRefreshThatFailsForOtherReasonsKeepsTheSession() = runBlocking<Unit> {
        server.onJson("GET", "/v1/me", """{"error":{"code":"unauthorized"}}""", code = 401)
        server.onJson("POST", "/v1/auth/refresh", """{"error":{"code":"upstream_failed"}}""", code = 500)
        assertTrue(me().errorOrNull!!.isUnauthorized)
        assertEquals("refresh-1", sessions.load()?.refreshToken)
    }

    @Test fun whenAnotherCallAlreadyRotatedTheTokenNoSecondRefreshHappens() = runBlocking<Unit> {
        // The call sent the old token; by the time its 401 is handled another call of the SAME sign-in has rotated it.
        server.onRequest("GET", "/v1/me") { req ->
            if (req.headers["Authorization"] == "Bearer access-1") {
                sessions.handle()!!.let { sessions.replaceIfCurrentBlocking(it, it.session.copy(accessToken = "access-new", refreshToken = "refresh-new")) }
                FakeServer.error(401, "unauthorized")
            } else {
                FakeServer.json(Samples.ME_ADMIN)
            }
        }
        assertNotNull(me().valueOrNull)
        assertEquals(0, server.requestsTo("POST", "/v1/auth/refresh").size)
        assertEquals("Bearer access-new", server.requestsTo("GET", "/v1/me").last().headers["Authorization"])
    }

    private fun u2() = testSession(server.baseUrl, "u2").copy(accessToken = "access-u2", refreshToken = "refresh-u2")

    @Test fun a401ForAnEarlierAccountIsNeverRetriedWithTheNewAccountsToken() = runBlocking<Unit> {
        server.onRequest("GET", "/v1/me") { req ->
            if (req.headers["Authorization"] == "Bearer access-1") {
                sessions.saveBlocking(u2()) // the user switched account while this request was in flight
                FakeServer.error(401, "unauthorized")
            } else {
                FakeServer.json(Samples.ME_ADMIN)
            }
        }
        assertTrue(me().errorOrNull!!.isUnauthorized)
        assertEquals(1, server.requestsTo("GET", "/v1/me").size, "not retried at all")
        assertEquals(0, server.requestsTo("POST", "/v1/auth/refresh").size, "the old account's refresh token is not used either")
        assertEquals("refresh-u2", sessions.load()?.refreshToken)
    }

    @Test fun aSignOutDuringARefreshWinsAndTheLateTokensAreDropped() = runBlocking<Unit> {
        server.onRequest("GET", "/v1/me") { req ->
            if (req.headers["Authorization"] == "Bearer access-1") FakeServer.error(401, "unauthorized") else FakeServer.json(Samples.ME_ADMIN)
        }
        server.onRequest("POST", "/v1/auth/refresh") {
            sessions.clearBlocking() // the user signs out while the refresh is on the wire
            FakeServer.json(Samples.session())
        }
        assertTrue(me().errorOrNull!!.isUnauthorized)
        assertNull(sessions.load(), "the refresh must not bring the old session back")
        assertEquals(1, server.requestsTo("GET", "/v1/me").size, "no retry with tokens of a dead session")
    }

    @Test fun aUserSwitchDuringARefreshKeepsTheNewUser() = runBlocking<Unit> {
        server.onRequest("GET", "/v1/me") { req ->
            if (req.headers["Authorization"] == "Bearer access-1") FakeServer.error(401, "unauthorized") else FakeServer.json(Samples.ME_ADMIN)
        }
        server.onRequest("POST", "/v1/auth/refresh") {
            sessions.saveBlocking(u2())
            FakeServer.json(Samples.session())
        }
        assertTrue(me().errorOrNull!!.isUnauthorized)
        val kept = sessions.load()
        assertEquals("u2", kept?.userId)
        assertEquals("refresh-u2", kept?.refreshToken)
        assertEquals(1, server.requestsTo("GET", "/v1/me").size)
    }

    @Test fun aRejectedRefreshOfAnEarlierSignInDoesNotSignTheNewOneOut() = runBlocking<Unit> {
        server.onJson("GET", "/v1/me", """{"error":{"code":"unauthorized"}}""", code = 401)
        server.onRequest("POST", "/v1/auth/refresh") {
            sessions.saveBlocking(u2())
            FakeServer.error(401, "unauthorized")
        }
        val events = java.util.concurrent.CopyOnWriteArrayList<SignOutReason>()
        val collector = async(kotlinx.coroutines.Dispatchers.Unconfined) { signedOut.events.collect { events.add(it) } }
        assertTrue(me().errorOrNull!!.isUnauthorized)
        collector.cancel()
        assertEquals("u2", sessions.load()?.userId)
        assertEquals(emptyList(), events.toList())
    }

    @Test fun twoConcurrent401sRefreshOnce() = runBlocking<Unit> {
        val barrier = java.util.concurrent.CyclicBarrier(2)
        server.onRequest("GET", "/v1/me") { req ->
            if (req.headers["Authorization"] == "Bearer access-1") {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS) // both calls are in flight with the old token
                FakeServer.error(401, "unauthorized")
            } else {
                FakeServer.json(Samples.ME_ADMIN)
            }
        }
        server.onJson("POST", "/v1/auth/refresh", Samples.session())
        val results = (1..2).map { async(kotlinx.coroutines.Dispatchers.IO) { me() } }.map { it.await() }
        assertTrue(results.all { it.valueOrNull != null })
        assertEquals(1, server.requestsTo("POST", "/v1/auth/refresh").size)
        assertEquals("refresh-new", sessions.load()?.refreshToken)
    }

    @Test fun theTokenNeverGoesToAnotherHostOrAnAuthRoute() = runBlocking<Unit> {
        val cdn = FakeServer()
        cdn.on("GET", "/file", FakeServer.json("{}"))
        server.on("POST", "/v1/auth/join", FakeServer.json(Samples.session()))
        try {
            factory.api().newCall(Request.Builder().url(cdn.baseUrl + "/file").build()).execute().close()
            assertNull(cdn.requests.single().headers["Authorization"])
            val api = provider.api()
            api.join(JoinRequest("code", "Bob", "Pixel"))
            assertNull(server.requests.single().headers["Authorization"])
        } finally {
            cdn.close()
        }
    }

    @Test fun theDownloadRouteIsNotFollowedSoTheCallerSeesTheRedirect() = runBlocking<Unit> {
        server.on("GET", "/v1/releases/${"a".repeat(40)}/files/0/download", MockResponse.Builder().code(302).addHeader("Location", "https://cdn.example/f?sig=1").build())
        val response = provider.api().downloadFile("a".repeat(40), 0, "torbox")
        assertEquals(302, response.code())
        assertEquals("https://cdn.example/f?sig=1", response.headers()["Location"])
    }

    @Test fun retryInterceptorBacksOffExponentiallyAndHonoursRetryAfter() {
        val slept = ArrayList<Long>()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor(RetryInterceptor(maxAttempts = 4, baseDelayMs = 100, maxRetryAfterMs = 5_000, sleeper = { slept += it })).build()
        server.on(
            "GET", "/x",
            FakeServer.error(503, "upstream_failed"),
            FakeServer.error(503, "upstream_failed", null, "Retry-After" to "2"),
            FakeServer.error(503, "upstream_failed", null, "Retry-After" to "999"),
            FakeServer.json("{}"),
        )
        client.newCall(Request.Builder().url(server.baseUrl + "/x").build()).execute().use { assertEquals(200, it.code) }
        assertEquals(listOf(100L, 2_000L, 5_000L), slept)
    }
}
