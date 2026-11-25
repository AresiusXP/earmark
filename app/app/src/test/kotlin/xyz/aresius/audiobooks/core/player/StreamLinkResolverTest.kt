package xyz.aresius.audiobooks.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import okhttp3.Request
import xyz.aresius.audiobooks.core.network.HttpClientFactory
import xyz.aresius.audiobooks.core.network.NetworkTimeouts
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.SignedOutEvents
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.memorySessionStore
import xyz.aresius.audiobooks.testutil.testSession

/** The stream link: 302 -> CDN link, 202 -> preparing, 403 -> needs a key; the token goes to the server's host only. */
class StreamLinkResolverTest {
    private val hash = "a".repeat(40)
    private val path = "/v1/releases/$hash/files/3/download"
    private val api = FakeServer()
    private val cdn = FakeServer()
    private val sessions = memorySessionStore(testSession(api.baseUrl))
    private val clients = HttpClientFactory(sessions, SignedOutEvents(), NetworkTimeouts(), emptyList())
    private val resolver = StreamLinkResolver({ clients.api() }, { sessions.snapshot()?.serverUrl })

    private fun resolve() = resolver.resolve(hash, 3, Provider.TorBox)

    @Test
    fun `a 302 gives the CDN link and the token went to the server only`() {
        val link = cdn.baseUrl + "/file.mp3?sig=abc"
        api.on("GET", path, MockResponse.Builder().code(302).addHeader("Location", link).build())
        assertEquals(link, resolve())
        val request = api.requestsTo("GET", path).single()
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertEquals("torbox", request.url.queryParameter("provider"))
        assertTrue(cdn.requests.isEmpty(), "the redirect is read, not followed, by the authenticated client")
    }

    @Test
    fun `the CDN hop uses the plain client, which never carries the token`() {
        cdn.on("GET", "/file.mp3", MockResponse.Builder().code(206).body("x").build())
        clients.plain().newCall(Request.Builder().url(cdn.baseUrl + "/file.mp3").header("Range", "bytes=0-").build()).execute().close()
        assertNull(cdn.requests.single().headers["Authorization"])
    }

    @Test
    fun `a relative Location is resolved against the server`() {
        api.on("GET", path, MockResponse.Builder().code(302).addHeader("Location", "/cdn/x.mp3").build())
        assertEquals(api.baseUrl + "/cdn/x.mp3", resolve())
    }

    @Test
    fun `202 is preparing with its progress, not an error page`() {
        api.on("GET", path, FakeServer.json("""{"state":"preparing","progress":37,"reason":"no_seeders"}""", 202))
        val e = assertFailsWith<StreamException.Preparing> { resolve() }
        assertEquals(37, e.prepare.progress)
        assertEquals("no_seeders", e.prepare.reason)
    }

    @Test
    fun `202 with an unreadable body is still preparing`() {
        api.on("GET", path, MockResponse.Builder().code(202).body("<html>").build())
        assertEquals("preparing", assertFailsWith<StreamException.Preparing> { resolve() }.prepare.state)
    }

    @Test
    fun `403 no_debrid_key and bad_debrid_key are the actionable key states`() {
        api.on("GET", path, FakeServer.error(403, "no_debrid_key", "add a key"))
        assertEquals(false, assertFailsWith<StreamException.NeedsKey> { resolve() }.badKey)
        api.on("GET", path, FakeServer.error(403, "bad_debrid_key", "rejected"))
        val bad = assertFailsWith<StreamException.NeedsKey> { resolve() }
        assertEquals(true, bad.badKey)
        assertEquals("rejected", bad.serverMessage)
    }

    @Test
    fun `other answers map to typed failures`() {
        api.on("GET", path, FakeServer.error(404, "not_found", "no such file"))
        assertIs<StreamException.NotFound>(assertFailsWith<StreamException> { resolve() })
        api.on("GET", path, FakeServer.error(429, "rate_limited", null, "Retry-After" to "30"))
        assertEquals(30, assertFailsWith<StreamException.RateLimited> { resolve() }.retryAfterS)
        api.on("GET", path, FakeServer.error(502, "upstream_failed", "no seeders"))
        val failed = assertFailsWith<StreamException.Failed> { resolve() }
        assertEquals(502, failed.status)
        assertEquals("no seeders", failed.serverMessage)
    }

    @Test
    fun `without a session nothing is requested`() {
        val signedOut = StreamLinkResolver({ clients.api() }) { null }
        assertFailsWith<StreamException.NotSignedIn> { signedOut.resolve(hash, 0, Provider.TorBox) }
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `every failure is an IOException so the player treats it as a load error`() {
        api.on("GET", path, FakeServer.error(403, "no_debrid_key"))
        assertFailsWith<java.io.IOException> { resolve() }
    }
}
