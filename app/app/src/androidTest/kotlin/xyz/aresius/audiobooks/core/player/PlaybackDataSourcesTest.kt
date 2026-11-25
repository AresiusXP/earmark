package xyz.aresius.audiobooks.core.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.aresius.audiobooks.core.auth.Session
import xyz.aresius.audiobooks.core.network.HttpClientFactory
import xyz.aresius.audiobooks.core.network.NetworkTimeouts
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.SignedOutEvents

/**
 * The real data source factory against two servers: the app's server (302 / 202 / 403, sees the token) and a CDN (Range requests, must never
 * see the token). Needs the Android `Uri`, so it cannot run as a plain unit test.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackDataSourcesTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hash = "b".repeat(40)
    private val api = MockWebServer()
    private val cdn = MockWebServer()
    private val cdnRequests = ArrayList<RecordedRequest>()
    private val bytes = ByteArray(1000) { (it % 251).toByte() }
    @Volatile private var apiAnswer: () -> MockResponse = { MockResponse.Builder().code(404).build() }

    @Before fun setUp() {
        cdn.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(cdnRequests) { cdnRequests.add(request) }
                val from = Regex("bytes=(\\d+)-").find(request.headers["Range"].orEmpty())?.groupValues?.get(1)?.toInt() ?: 0
                val body = okio.Buffer().write(bytes, from, bytes.size - from)
                return MockResponse.Builder().code(if (from > 0) 206 else 200)
                    .addHeader("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}").addHeader("Accept-Ranges", "bytes").body(body).build()
            }
        }
        api.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = apiAnswer()
        }
        cdn.start()
        api.start()
    }

    @After fun tearDown() {
        api.close()
        cdn.close()
    }

    private fun factory(): PlaybackDataSources {
        val sessions = memoryStore(Session(api.url("/").toString().trimEnd('/'), "access-token-1", "refresh-1", 0, "test", "u1"))
        return PlaybackDataSources(context, HttpClientFactory(sessions, SignedOutEvents(), NetworkTimeouts(), emptyList()), sessions)
    }

    private fun open(position: Long = 0): ByteArray {
        val source = factory().factory().createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(StreamUri.of(hash, 3, Provider.TorBox)).setPosition(position).build())
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(256)
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            source.close()
        }
    }

    @Test fun streamsThroughTheRedirectWithRangeAndNoTokenOnTheCdn() {
        apiAnswer = { MockResponse.Builder().code(302).addHeader("Location", cdn.url("/file.mp3").toString()).build() }
        assertTrue(bytes.copyOfRange(100, 1000).contentEquals(open(position = 100)))
        val request = api.takeRequest()
        assertEquals("/v1/releases/$hash/files/3/download?provider=torbox", request.url.encodedPath + "?" + request.url.encodedQuery)
        assertEquals("Bearer access-token-1", request.headers["Authorization"])
        val seen = synchronized(cdnRequests) { cdnRequests.toList() }
        assertTrue(seen.isNotEmpty())
        seen.forEach { assertNull(it.headers["Authorization"], "the token must not reach the CDN") }
        assertEquals("bytes=100-", seen.first().headers["Range"])
    }

    @Test fun preparingAndMissingKeyAreTypedErrors() {
        apiAnswer = { MockResponse.Builder().code(202).addHeader("Content-Type", "application/json").body("""{"state":"preparing","progress":12}""").build() }
        val preparing = runCatching { open() }.exceptionOrNull()
        assertTrue(preparing is StreamException.Preparing, "was $preparing")
        assertEquals(12, preparing.prepare.progress)
        apiAnswer = { MockResponse.Builder().code(403).addHeader("Content-Type", "application/json").body("""{"error":{"code":"no_debrid_key","message":"add one"}}""").build() }
        val needsKey = runCatching { open() }.exceptionOrNull()
        assertTrue(needsKey is StreamException.NeedsKey && !needsKey.badKey, "was $needsKey")
        assertTrue(cdnRequests.isEmpty())
    }
}
