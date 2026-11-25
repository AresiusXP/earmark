package xyz.aresius.audiobooks.debug

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import xyz.aresius.audiobooks.core.network.ApiErrorBody
import xyz.aresius.audiobooks.core.network.ApiErrorDetail
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Prepare

/**
 * The demo server's side of offline downloads: `prepare` that needs a few polls, the download route (202 until ready, then 302) and a
 * "CDN" under `/cdn/<hash>/<index>.mp3` of the same host that serves a silent WAV of [FILE_BYTES] with Range support (playable, and the size
 * the release lists, so the app's integrity check passes). Not for production: it only exists in the debug source set.
 */
internal class DemoDownloads {
    private val polls = HashMap<String, Int>()

    fun prepare(request: Request, hash: String, files: List<Pair<Int, String>>, hasKey: Boolean): Response {
        if (!hasKey) return error(request, 403, "no_debrid_key", "add a debrid key first")
        val n = polls.merge(hash, 1, Int::plus)!!
        val body = if (n < READY_AFTER) {
            Prepare(Prepare.PREPARING, progress = n * 100 / READY_AFTER, retryAfterS = 2)
        } else {
            Prepare(Prepare.READY, files = files.map { (index, path) -> xyz.aresius.audiobooks.core.network.TorrentFile(index, path, FILE_BYTES) })
        }
        return json(request, 200, AudiobooksJson.encodeToString(Prepare.serializer(), body))
    }

    fun download(request: Request, hash: String, index: Int, hasKey: Boolean): Response {
        if (!hasKey) return error(request, 403, "no_debrid_key", "add a debrid key first")
        if ((polls[hash] ?: 0) < READY_AFTER) {
            return json(request, 202, AudiobooksJson.encodeToString(Prepare.serializer(), Prepare(Prepare.PREPARING, progress = 20, retryAfterS = 2)))
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(302).message("Found")
            .header("Location", "http://${FakeBackend.HOST}/cdn/$hash/$index.mp3").body(ByteArray(0).toResponseBody(null)).build()
    }

    fun cdn(request: Request): Response {
        val range = request.header("Range")?.substringAfter("bytes=", "")?.substringBefore('-')?.toLongOrNull()
        val start = range?.coerceIn(0, FILE_BYTES) ?: 0L
        val length = FILE_BYTES - start
        val body = object : ResponseBody() {
            override fun contentType() = "audio/mpeg".toMediaType()
            override fun contentLength() = length
            override fun source(): BufferedSource = Silence(start).buffer()
        }
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("OK").body(body)
        return if (range != null) {
            builder.code(206).header("Content-Range", "bytes $start-${FILE_BYTES - 1}/$FILE_BYTES").build()
        } else {
            builder.code(200).build()
        }
    }

    private fun json(request: Request, code: Int, text: String) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("OK").header("Cache-Control", "no-store")
            .body(text.toResponseBody("application/json".toMediaType())).build()

    private fun error(request: Request, status: Int, code: String, message: String) =
        json(request, status, AudiobooksJson.encodeToString(ApiErrorBody.serializer(), ApiErrorBody(ApiErrorDetail(code, message))))

    /** A WAV (8 kHz, 8-bit, mono) of [FILE_BYTES] bytes in total: a 44-byte header, then silence; read from [position]. */
    private class Silence(private var position: Long) : Source {
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (position >= FILE_BYTES) return -1
            val n = minOf(byteCount, FILE_BYTES - position, 8192L).toInt()
            val chunk = ByteArray(n) { i -> byteAt(position + i) }
            sink.write(chunk)
            position += n
            return n.toLong()
        }

        override fun timeout() = Timeout.NONE
        override fun close() = Unit

        private fun byteAt(p: Long): Byte = if (p < HEADER.size) HEADER[p.toInt()] else 0x80.toByte()
    }

    companion object {
        const val FILE_BYTES = 4L * 1024 * 1024
        private const val READY_AFTER = 3
        private val HEADER: ByteArray = run {
            val dataLen = (FILE_BYTES - 44).toInt()
            val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            b.putInt(16).putShort(1).putShort(1).putInt(8000).putInt(8000).putShort(1).putShort(8)
            b.put("data".toByteArray()).putInt(dataLen)
            b.array()
        }
    }
}
