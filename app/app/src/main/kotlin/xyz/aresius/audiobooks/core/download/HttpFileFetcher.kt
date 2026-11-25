package xyz.aresius.audiobooks.core.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Why one attempt to fetch a file failed. */
class FetchFailure(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        /** Network trouble or a 5xx: try again later; the `.part` file is kept so the next attempt resumes. */
        Transient,

        /** The link is no longer valid (401/403/404/410): ask the server for a fresh one. */
        LinkGone,

        /** A 2xx that is not the audio (an error page, JSON, a tiny placeholder). Never saved. */
        BadContent,

        /** The size does not match what the server reported. The partial data is dropped. */
        SizeMismatch,

        /** The phone's storage is full. */
        DiskFull,
    }
}

/**
 * What a download may be saved as. An explicit accept policy, not a deny-list: `audio/<anything>` and the MP4 family are accepted by their type;
 * the generic binary types (`application/octet-stream`, `binary/octet-stream`, and an absent type) only when the first bytes are a known
 * audio container; everything else (text, JSON, HTML, XML, images, archives, other video, ...) is refused.
 */
internal object AudioContent {
    enum class Verdict { Accept, Sniff, Reject }

    const val HEAD_BYTES = 16

    private val ACCEPT = setOf("video/mp4", "audio/mp4", "application/mp4")
    private val SNIFF = setOf("application/octet-stream", "binary/octet-stream", "")

    fun classify(contentType: String): Verdict {
        val type = contentType.substringBefore(';').trim().lowercase()
        return when {
            type.startsWith("audio/") && type.length > "audio/".length -> Verdict.Accept
            type in ACCEPT -> Verdict.Accept
            type in SNIFF -> Verdict.Sniff
            else -> Verdict.Reject
        }
    }

    /** ID3, an MPEG audio / ADTS frame, MP4 (`ftyp`), Ogg, FLAC, RIFF/WAVE, Matroska/WebM or ASF/WMA at the start of [h]. */
    fun looksLikeAudio(h: ByteArray): Boolean {
        fun at(offset: Int, text: String) = h.size >= offset + text.length && text.indices.all { h[offset + it] == text[it].code.toByte() }
        fun u(i: Int) = if (i < h.size) h[i].toInt() and 0xFF else -1
        return at(0, "ID3") ||
            at(4, "ftyp") ||
            at(0, "OggS") ||
            at(0, "fLaC") ||
            (at(0, "RIFF") && at(8, "WAVE")) ||
            (u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3) ||
            (h.size >= 8 && intArrayOf(0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11).withIndex().all { (i, v) -> u(i) == v }) ||
            isMpegFrame(h)
    }

    /** Frame sync (11 bits set), a valid version and layer (MPEG audio) or layer 00 (ADTS), and a bitrate index that is not 1111. */
    private fun isMpegFrame(h: ByteArray): Boolean {
        if (h.size < 3) return false
        val b1 = h[1].toInt() and 0xFF
        val b2 = h[2].toInt() and 0xFF
        if ((h[0].toInt() and 0xFF) != 0xFF || (b1 and 0xE0) != 0xE0) return false
        val version = (b1 shr 3) and 0x3
        val layer = (b1 shr 1) and 0x3
        if (version == 1) return false
        if (layer == 0) return true // ADTS (AAC): the layer bits are always 00
        return (b2 shr 4) != 0xF
    }
}

/**
 * Downloads ONE file with HTTP Range resume: bytes go to `<dest>.part`, which is renamed atomically to [dest] only when complete and
 * verified. Only a 200/206 whose content type passes [AudioContent] (and, for a generic binary type, whose first bytes are a known audio container) is ever written. [client] must not add credentials (it talks to a CDN).
 */
class HttpFileFetcher(private val client: OkHttpClient) {

    /** Returns the final size of [dest]. [onProgress] gets the bytes on disk (including the resumed part), throttled. */
    suspend fun fetch(url: String, dest: File, expectedSize: Long, onProgress: suspend (Long) -> Unit): Long = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + PART_SUFFIX)
        var offset = if (part.isFile) part.length() else 0L
        if (expectedSize > 0 && offset > expectedSize) {
            part.delete()
            offset = 0
        }
        if (expectedSize > 0 && offset == expectedSize) return@withContext finish(part, dest, expectedSize)

        val request = Request.Builder().url(url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        val call = client.newCall(request)
        val response = try {
            call.await()
        } catch (e: IOException) {
            throw FetchFailure(FetchFailure.Kind.Transient, "connection failed", e)
        }
        response.use { r ->
            val code = r.code
            when {
                code == 416 -> {
                    part.delete()
                    throw FetchFailure(FetchFailure.Kind.Transient, "range not satisfiable; restarting")
                }
                code == 401 || code == 403 || code == 404 || code == 410 -> throw FetchFailure(FetchFailure.Kind.LinkGone, "link refused ($code)")
                code == 429 || code >= 500 -> throw FetchFailure(FetchFailure.Kind.Transient, "server answered $code")
                code != 200 && code != 206 -> throw FetchFailure(FetchFailure.Kind.BadContent, "unexpected status $code")
            }
            val type = r.header("Content-Type").orEmpty()
            val needsSniff = when (AudioContent.classify(type)) {
                AudioContent.Verdict.Accept -> false
                AudioContent.Verdict.Sniff -> true
                AudioContent.Verdict.Reject -> throw FetchFailure(FetchFailure.Kind.BadContent, "not audio (content type ${type.take(80)})")
            }
            // A generic binary type proves nothing: the first bytes must be a known audio container (before anything is written).
            if (needsSniff) {
                val head = if (offset > 0 && code == 206) readHead(part) else r.peekBody(AudioContent.HEAD_BYTES.toLong()).bytes()
                if (!AudioContent.looksLikeAudio(head)) {
                    if (offset > 0 && code == 206) part.delete()
                    throw FetchFailure(FetchFailure.Kind.BadContent, "not audio (generic content type without an audio container)")
                }
            }
            val resumed = code == 206
            if (resumed) {
                val start = r.header("Content-Range")?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()
                if (start != offset) {
                    part.delete()
                    throw FetchFailure(FetchFailure.Kind.Transient, "server resumed at a different offset")
                }
            } else if (offset > 0) {
                part.delete() // The server ignored the Range header: start over.
                offset = 0
            }
            val length = r.header("Content-Length")?.toLongOrNull() ?: -1L
            if (expectedSize > 0 && length >= 0 && offset + length != expectedSize) {
                part.delete()
                throw FetchFailure(FetchFailure.Kind.SizeMismatch, "server announced ${offset + length} bytes, expected $expectedSize")
            }
            val written = copy(call, r, part, append = offset > 0, length, offset, onProgress)
            val total = offset + written
            if (length >= 0 && written != length) throw FetchFailure(FetchFailure.Kind.Transient, "connection closed early")
            if (expectedSize > 0 && total < expectedSize) throw FetchFailure(FetchFailure.Kind.Transient, "connection closed early")
            finish(part, dest, expectedSize).also { onProgress(it) }
        }
    }

    private fun readHead(file: File): ByteArray = file.inputStream().use { input ->
        val buf = ByteArray(AudioContent.HEAD_BYTES)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        buf.copyOf(n)
    }

    private suspend fun copy(call: Call, r: Response, part: File, append: Boolean, length: Long, offset: Long, onProgress: suspend (Long) -> Unit): Long =
        coroutineScope {
            // Cancelling the coroutine (worker stopped) must unblock a read that waits on the network.
            val watchdog = launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                var written = 0L
                var lastReport = System.nanoTime()
                var lastReported = 0L
                val buffer = ByteArray(BUFFER)
                try {
                    FileOutputStream(part, append).use { out ->
                        r.body.byteStream().use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                written += n
                                ensureActive()
                                val now = System.nanoTime()
                                if (written - lastReported >= REPORT_BYTES || now - lastReport >= REPORT_NANOS) {
                                    onProgress(offset + written)
                                    lastReport = now
                                    lastReported = written
                                }
                            }
                        }
                        out.flush()
                        out.fd.sync()
                    }
                } catch (e: IOException) {
                    ensureActive()
                    val text = e.message.orEmpty()
                    if (text.contains("ENOSPC") || text.contains("No space left")) throw FetchFailure(FetchFailure.Kind.DiskFull, "storage full", e)
                    throw FetchFailure(FetchFailure.Kind.Transient, "download interrupted", e)
                }
                if (length >= 0 && written > length) throw FetchFailure(FetchFailure.Kind.SizeMismatch, "more bytes than announced")
                written
            } finally {
                watchdog.cancel()
            }
        }

    /** Verifies the size and renames `.part` to the final name in one atomic step. */
    private fun finish(part: File, dest: File, expectedSize: Long): Long {
        val size = part.length()
        if (expectedSize > 0 && size != expectedSize) {
            part.delete()
            throw FetchFailure(FetchFailure.Kind.SizeMismatch, "got $size bytes, expected $expectedSize")
        }
        if (expectedSize <= 0 && size < MIN_AUDIO_BYTES) {
            part.delete()
            throw FetchFailure(FetchFailure.Kind.BadContent, "only $size bytes: not an audio file")
        }
        Files.move(part.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return size
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, _, _ -> response.close() }
            }
        })
    }

    companion object {
        const val PART_SUFFIX = ".part"
        const val MIN_AUDIO_BYTES = 1024L
        private const val BUFFER = 64 * 1024
        private const val REPORT_BYTES = 512 * 1024L
        private const val REPORT_NANOS = 500_000_000L
    }
}
