package xyz.aresius.audiobooks.core.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ErrorCode
import xyz.aresius.audiobooks.core.network.Prepare
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.TorrentFile
import xyz.aresius.audiobooks.core.network.errorOrNull
import xyz.aresius.audiobooks.core.network.valueOrNull
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.TestApp

class ReleaseRepositoryTest {
    // Like the app's API client: redirects are not followed, the caller reads Location.
    private val server = FakeServer(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build())
    private val app = TestApp(server)
    private val hash = "a".repeat(40)

    @AfterTest fun tearDown() = server.close()

    @Test fun prepareSendsTheProviderAndReturnsTheState() = runBlocking<Unit> {
        server.onJson("POST", "/v1/releases/$hash/prepare", """{"state":"preparing","progress":40}""")
        val p = app.releases.prepare(hash, Provider.TorBox).valueOrNull!!
        assertEquals(Prepare.PREPARING, p.state)
        assertEquals(40, p.progress)
        assertEquals("""{"provider":"torbox"}""", server.lastBody("POST", "/v1/releases/$hash/prepare"))
        server.on("POST", "/v1/releases/$hash/prepare", FakeServer.error(403, "no_debrid_key"))
        assertEquals(ErrorCode.NoDebridKey, (app.releases.prepare(hash, Provider.RealDebrid, "magnet:?xt=urn:btih:$hash").errorOrNull as ApiError.Http).code)
    }

    @Test fun a302GivesTheCdnLinkWithoutFollowingIt() = runBlocking<Unit> {
        server.on("GET", "/v1/releases/$hash/files/2/download", MockResponse.Builder().code(302).addHeader("Location", "https://cdn.example/file?sig=abc").build())
        val link = app.releases.downloadLink(hash, 2, Provider.TorBox).valueOrNull
        assertIs<DownloadLink.Redirect>(link)
        assertEquals("https://cdn.example/file?sig=abc", link.url)
        assertFalse(link.toString().contains("sig"), "the signed link must stay out of logs")
        assertEquals("torbox", server.requests.single().url.queryParameter("provider"))
    }

    @Test fun a202MeansStillPreparing() = runBlocking<Unit> {
        server.onJson("GET", "/v1/releases/$hash/files/0/download", """{"state":"preparing","progress":10,"retry_after_s":5}""", code = 202)
        val link = app.releases.downloadLink(hash, 0, Provider.RealDebrid).valueOrNull
        assertIs<DownloadLink.NotReady>(link)
        assertEquals(5, link.prepare.retryAfterS)
    }

    @Test fun errorsOfTheDownloadRouteAreTyped() = runBlocking<Unit> {
        server.on("GET", "/v1/releases/$hash/files/0/download", FakeServer.error(403, "bad_debrid_key", "RD said no"))
        val e = app.releases.downloadLink(hash, 0, Provider.RealDebrid).errorOrNull as ApiError.Http
        assertEquals(ErrorCode.BadDebridKey, e.code)
        assertEquals("RD said no", e.message)
    }

    @Test fun torrentFilesAreReported() = runBlocking<Unit> {
        server.onJson("POST", "/v1/releases/$hash/torrent-files", """{"torrent_files":[{"index":0,"path":"a/01.mp3","size":10}]}""")
        val stored = app.releases.reportTorrentFiles(hash, listOf(TorrentFile(0, "a/01.mp3", 10))).valueOrNull!!
        assertEquals("a/01.mp3", stored.single().path)
        assertEquals("""{"files":[{"index":0,"path":"a/01.mp3","size":10}]}""", server.lastBody("POST", "/v1/releases/$hash/torrent-files"))
    }
}
