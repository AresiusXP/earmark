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

    // ---- accept policy: audio/*, the MP4 family, generic binary only with an audio container ----

    private fun withHead(vararg head: Int): ByteArray = data.copyOf().also { head.forEachIndexed { i, b -> it[i] = b.toByte() } }
    private fun ascii(text: String, at: Int = 0): ByteArray = data.copyOf().also { text.forEachIndexed { i, c -> it[at + i] = c.code.toByte() } }

    private fun serve(type: String?, body: ByteArray) {
        val r = MockResponse.Builder().code(200).body(Buffer().write(body))
        if (type != null) r.addHeader("Content-Type", type)
        server.enqueue(r.build())
    }

    private fun assertSaved(type: String?, body: ByteArray) {
        dest.delete()
        serve(type, body)
        assertEquals(body.size.toLong(), fetch(expected = body.size.toLong()), "type=$type")
        assertContentEquals(body, dest.readBytes())
    }

    private fun assertRefused(type: String?, body: ByteArray = data) {
        serve(type, body)
        assertEquals(FetchFailure.Kind.BadContent, assertFailsWith<FetchFailure> { fetch(expected = body.size.toLong()) }.kind, "type=$type")
        assertFalse(dest.exists(), "type=$type")
        assertFalse(part.exists(), "type=$type")
    }

    @Test fun audioTypesAndTheMp4FamilyAreAcceptedByTheirType() {
        for (type in listOf("audio/mpeg", "audio/mp4", "audio/x-m4b", "audio/ogg", "AUDIO/MPEG; charset=binary", "video/mp4", "application/mp4")) assertSaved(type, data)
    }

    @Test fun everythingElseIsRefusedWhateverTheBody() {
        for (type in listOf(
            "text/plain", "text/html; charset=utf-8", "application/json", "application/xml", "application/pdf", "application/zip", "image/png", "image/jpeg",
            "application/x-msdownload", "application/vnd.android.package-archive", "video/webm", "video/x-matroska", "multipart/form-data", "audio/", "foo",
        )) assertRefused(type, withHead(0x49, 0x44, 0x33)) // even with an ID3 head
    }

    @Test fun aGenericBinaryTypeNeedsAKnownAudioContainer() {
        val containers = mapOf(
            "ID3" to withHead(0x49, 0x44, 0x33, 4, 0),
            "MPEG layer III" to withHead(0xFF, 0xFB, 0x90, 0x44),
            "MPEG layer II 2.5" to withHead(0xFF, 0xE3, 0x20, 0x00),
            "ADTS" to withHead(0xFF, 0xF1, 0x50, 0x80),
            "MP4" to ascii("ftypM4B ", at = 4),
            "Ogg" to ascii("OggS"),
            "FLAC" to ascii("fLaC"),
            "WAVE" to ascii("RIFF").also { "WAVE".forEachIndexed { i, c -> it[8 + i] = c.code.toByte() } },
            "Matroska" to withHead(0x1A, 0x45, 0xDF, 0xA3),
            "ASF" to withHead(0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11),
        )
        for (type in listOf("application/octet-stream", "binary/octet-stream", "Application/Octet-Stream; x=y", null)) {
            for ((name, body) in containers) {
                dest.delete()
                serve(type, body)
                assertEquals(body.size.toLong(), fetch(expected = body.size.toLong()), "$name as $type")
            }
        }
    }

    @Test fun aGenericBinaryTypeWithoutAnAudioContainerIsRefused() {
        val notAudio = mapOf(
            "html" to "<!doctype html><html>".toByteArray().copyOf(data.size),
            "json" to """{"state":"preparing"}""".toByteArray().copyOf(data.size),
            "zip" to ascii("PK\u0003\u0004"),
            "pe" to ascii("MZ"),
            "pdf" to ascii("%PDF-1.7"),
            "zeros" to ByteArray(data.size),
            "bad mpeg bitrate" to withHead(0xFF, 0xFB, 0xF0, 0x00),
            "reserved mpeg version" to withHead(0xFF, 0xEB, 0x90, 0x00),
            "riff but not wave" to ascii("RIFF").also { "AVI ".forEachIndexed { i, c -> it[8 + i] = c.code.toByte() } },
        )
        for (type in listOf("application/octet-stream", "binary/octet-stream", null)) {
            for ((name, body) in notAudio) assertRefused(type, body).also { assertTrue(true, name) }
        }
    }

    @Test fun aResumedGenericBinaryFileIsCheckedAgainstTheHeadOfThePartFile() {
        val have = 70_000L
        val body = withHead(0x49, 0x44, 0x33, 4, 0)
        part.parentFile!!.mkdirs()
        part.writeBytes(body.copyOfRange(0, have.toInt()))
        server.enqueue(
            MockResponse.Builder().code(206).addHeader("Content-Type", "application/octet-stream")
                .addHeader("Content-Range", "bytes $have-${body.size - 1}/${body.size}").body(Buffer().write(body.copyOfRange(have.toInt(), body.size))).build(),
        )
        assertEquals(body.size.toLong(), fetch(expected = body.size.toLong()))
        assertContentEquals(body, dest.readBytes())

        // a part file that does not start with an audio container is dropped, not completed
        dest.delete()
        part.writeBytes(ByteArray(have.toInt()))
        server.enqueue(
            MockResponse.Builder().code(206).addHeader("Content-Type", "application/octet-stream")
                .addHeader("Content-Range", "bytes $have-${body.size - 1}/${body.size}").body(Buffer().write(body.copyOfRange(have.toInt(), body.size))).build(),
        )
        assertEquals(FetchFailure.Kind.BadContent, assertFailsWith<FetchFailure> { fetch(expected = body.size.toLong()) }.kind)
        assertFalse(dest.exists())
        assertFalse(part.exists())
    }
}
