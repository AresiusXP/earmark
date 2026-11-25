package xyz.aresius.audiobooks.core.download

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import xyz.aresius.audiobooks.testutil.Audio

class HttpFileFetcherTest {
    private val server = MockWebServer().also { it.start() }
    private val dir: File = Files.createTempDirectory("fetch").toFile()
    private val fetcher = HttpFileFetcher(OkHttpClient())
    private val data = Audio.bytes("chapter", 200_000)
    private val dest = File(dir, "book/ch1.mp3")
    private val part = File(dir, "book/ch1.mp3.part")
    private val url get() = server.url("/cdn/ch1").toString()

    @AfterTest fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun fetch(expected: Long = data.size.toLong(), progress: MutableList<Long> = ArrayList()) =
        runBlocking { fetcher.fetch(url, dest, expected) { progress.add(it) } }

    @Test fun downloadsToPartThenRenamesAtomically() {
        server.enqueue(Audio.response(data))
        val progress = ArrayList<Long>()
        assertEquals(data.size.toLong(), fetch(progress = progress))
        assertContentEquals(data, dest.readBytes())
        assertFalse(part.exists(), "no .part is left behind")
        assertEquals(data.size.toLong(), progress.last())
        assertEquals(null, server.takeRequest().headers["Range"], "a fresh download sends no Range header")
    }

    @Test fun resumesAPartialFileWithARangeRequest() {
        part.parentFile!!.mkdirs()
        part.writeBytes(data.copyOfRange(0, 70_000))
        val rest = data.copyOfRange(70_000, data.size)
        server.enqueue(Audio.response(rest, code = 206, headers = arrayOf("Content-Range" to "bytes 70000-199999/200000")))
        fetch()
        assertEquals("bytes=70000-", server.takeRequest().headers["Range"])
        assertContentEquals(data, dest.readBytes())
        assertFalse(part.exists())
    }

    @Test fun startsOverWhenTheServerIgnoresTheRange() {
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(50_000) { 7 })
        server.enqueue(Audio.response(data)) // 200 with the whole file
        fetch()
        assertContentEquals(data, dest.readBytes())
    }

    @Test fun anInterruptedDownloadKeepsThePartFileForTheNextResume() {
        val cut = MockResponse.Builder().code(200).addHeader("Content-Type", "audio/mpeg")
            .body(Buffer().write(data.copyOfRange(0, 90_000))).setHeader("Content-Length", data.size).onResponseEnd(SocketEffect.CloseSocket()).build()
        server.enqueue(cut)
        val e = assertFailsWith<FetchFailure> { fetch() }
        assertEquals(FetchFailure.Kind.Transient, e.kind)
        assertFalse(dest.exists(), "an incomplete file is never published under its final name")
        assertTrue(part.length() in 1 until data.size, "the partial bytes are kept")
        // ... and the next attempt resumes from where it stopped.
        val have = part.length()
        server.enqueue(Audio.response(data.copyOfRange(have.toInt(), data.size), code = 206, headers = arrayOf("Content-Range" to "bytes $have-${data.size - 1}/${data.size}")))
        fetch()
        assertContentEquals(data, dest.readBytes())
    }

    @Test fun aJsonBodyIsNeverSavedAsAudio() {
        server.enqueue(Audio.response("""{"state":"preparing","retry_after_s":5}""".toByteArray(), type = "application/json"))
        assertEquals(FetchFailure.Kind.BadContent, assertFailsWith<FetchFailure> { fetch() }.kind)
        assertFalse(dest.exists())
        assertFalse(part.exists())
    }

    @Test fun anHtmlErrorPageIsNeverSavedAsAudio() {
        server.enqueue(Audio.response("<html>nope</html>".toByteArray(), type = "text/html; charset=utf-8"))
        assertEquals(FetchFailure.Kind.BadContent, assertFailsWith<FetchFailure> { fetch() }.kind)
        assertFalse(dest.exists())
        assertFalse(part.exists())
    }

    @Test fun aStatus202IsNotAudioEither() {
        server.enqueue(Audio.response("{}".toByteArray(), code = 202))
        assertEquals(FetchFailure.Kind.BadContent, assertFailsWith<FetchFailure> { fetch() }.kind)
        assertFalse(dest.exists())
        assertFalse(part.exists())
    }

    @Test fun aTinyBodyWithUnknownSizeIsNotAudio() {
        server.enqueue(Audio.response(ByteArray(100)))
        assertEquals(FetchFailure.Kind.BadContent, assertFailsWith<FetchFailure> { fetch(expected = 0) }.kind)
        assertFalse(dest.exists())
        assertFalse(part.exists())
    }

    @Test fun aWrongSizeFailsTheIntegrityCheckAndDropsTheData() {
        server.enqueue(Audio.response(data))
        assertEquals(FetchFailure.Kind.SizeMismatch, assertFailsWith<FetchFailure> { fetch(expected = data.size + 10L) }.kind)
        assertFalse(dest.exists())
        assertFalse(part.exists())
    }

    @Test fun errorStatusesMapToRetryAndFreshLink() {
        server.enqueue(MockResponse.Builder().code(403).body("denied").build())
        assertEquals(FetchFailure.Kind.LinkGone, assertFailsWith<FetchFailure> { fetch() }.kind)
        server.enqueue(MockResponse.Builder().code(503).body("busy").build())
        assertEquals(FetchFailure.Kind.Transient, assertFailsWith<FetchFailure> { fetch() }.kind)
        assertFalse(dest.exists())
    }

    @Test fun aCompletePartFileJustGetsPublished() {
        part.parentFile!!.mkdirs()
        part.writeBytes(data)
        fetch()
        assertContentEquals(data, dest.readBytes())
        assertEquals(0, server.requestCount, "nothing to fetch")
    }
}
