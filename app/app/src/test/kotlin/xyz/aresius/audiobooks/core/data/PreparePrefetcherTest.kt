package xyz.aresius.audiobooks.core.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.ReleaseView
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.await

class PreparePrefetcherTest {
    private val server = FakeServer()
    private val app = TestApp(server)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var now = 1_000_000L
    private val prefetcher = PreparePrefetcher(app.releases, app.sessions, scope, { now }, ttlMs = 5 * 60_000L)
    private val hash = "c".repeat(40)

    @AfterTest fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun release(h: String = hash, usable: String = """{"debrid":["torbox"],"p2p":false}""", cached: String = """{"torbox":true}""") =
        AudiobooksJson.decodeFromString(ReleaseView.serializer(), Samples.release(h, usable, cached))

    private fun prepares() = server.requestsTo("POST", "/v1/releases/$hash/prepare")

    @Test fun `a cached release is prepared with the provider the player would use, once per window`() {
        server.onJson("POST", "/v1/releases/$hash/prepare", """{"state":"ready","files":[]}""")
        assertTrue(prefetcher.prefetch(release(), Provider.TorBox))
        server.await { prepares().takeIf { it.size == 1 } }
        assertTrue(prepares().single().body!!.utf8().contains("torbox"))
        now += 60_000
        assertFalse(prefetcher.prefetch(release(), Provider.TorBox), "asked a minute ago")
        now += 5 * 60_000
        assertTrue(prefetcher.prefetch(release(), Provider.TorBox), "the window is over")
        server.await { prepares().takeIf { it.size == 2 } }
    }

    @Test fun `an uncached release or a provider the user cannot use is never prepared by looking at a page`() {
        assertFalse(prefetcher.prefetch(release(cached = """{"torbox":false}"""), Provider.TorBox))
        assertFalse(prefetcher.prefetch(release(cached = "{}"), Provider.TorBox))
        assertFalse(prefetcher.prefetch(release(usable = """{"debrid":[],"p2p":true}"""), Provider.TorBox))
        assertFalse(prefetcher.prefetch(release(cached = """{"realdebrid":true}""", usable = """{"debrid":["torbox"],"p2p":false}"""), Provider.RealDebrid))
        Thread.sleep(100)
        assertEquals(0, server.requests.size)
    }

    @Test fun `a failed prepare is not retried inside the window`() {
        server.on("POST", "/v1/releases/$hash/prepare", FakeServer.error(429, "rate_limited"))
        assertTrue(prefetcher.prefetch(release(), Provider.TorBox))
        server.await { prepares().takeIf { it.size == 1 } }
        assertFalse(prefetcher.prefetch(release(), Provider.TorBox))
        Thread.sleep(100)
        assertEquals(1, prepares().size)
    }
}
