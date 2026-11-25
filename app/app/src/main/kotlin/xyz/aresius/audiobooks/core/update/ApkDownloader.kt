package xyz.aresius.audiobooks.core.update

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** The fixed path of the APK on the server: the app never takes a download URL from a response. */
const val APP_UPDATE_APK_PATH = "v1/app/update/apk"

/**
 * Streams `GET /v1/app/update/apk` (a public route) into `<dir>/audiobooks-<version>.apk.part`, verifies size and SHA-256 against the
 * server's metadata and renames it to `.apk`. No resume: the APK is small. A failed or cancelled transfer deletes the partial file.
 */
class ApkDownloader(
    private val client: OkHttpClient,
    private val files: UpdateFiles,
    private val freeSpace: (File) -> Long,
) {
    suspend fun download(serverUrl: String, update: AppUpdateInfo, onProgress: (bytes: Long, total: Long) -> Unit): DownloadOutcome =
        withContext(Dispatchers.IO) {
            val url = (serverUrl.trimEnd('/') + "/").toHttpUrlOrNull()?.newBuilder()?.addPathSegments(APP_UPDATE_APK_PATH)?.build()
                ?: return@withContext DownloadOutcome.Failure(UpdateError.Network)
            if (!update.isWellFormed()) return@withContext DownloadOutcome.Failure(UpdateError.Corrupt)
            files.ensureDir()
            files.cleanupExcept(update.versionName)
            val apk = files.apk(update.versionName)
            if (apk.exists()) {
                if (ApkVerifier.matches(apk, update)) return@withContext DownloadOutcome.Success(apk)
                apk.delete()
            }
            if (freeSpace(files.dir) < update.size + STORAGE_MARGIN) return@withContext DownloadOutcome.Failure(UpdateError.InstallStorage)
            val part = files.part(update.versionName)
            part.delete()
            val call = client.newCall(Request.Builder().url(url).header("Accept-Encoding", "identity").build())
            try {
                coroutineScope {
                    // Cancelling the coroutine must also interrupt a blocked socket read.
                    val watcher = launch {
                        try {
                            awaitCancellation()
                        } finally {
                            call.cancel()
                        }
                    }
                    try {
                        val response = try {
                            call.execute()
                        } catch (e: IOException) {
                            coroutineContext.ensureActive()
                            return@coroutineScope DownloadOutcome.Failure(UpdateError.Network)
                        }
                        response.use { r ->
                            when {
                                r.code == 404 -> DownloadOutcome.Failure(UpdateError.NotAvailable)
                                !r.isSuccessful -> DownloadOutcome.Failure(UpdateError.Server(r.code))
                                r.body.contentLength().let { it >= 0 && it != update.size } -> DownloadOutcome.Failure(UpdateError.Corrupt)
                                else -> copy(r.body.byteStream(), part, update, onProgress)?.let { DownloadOutcome.Failure(it) } ?: finish(part, apk, update)
                            }
                        }
                    } finally {
                        watcher.cancel()
                    }
                }
            } catch (e: CancellationException) {
                part.delete()
                throw e
            }
        }

    private suspend fun copy(source: java.io.InputStream, part: File, update: AppUpdateInfo, onProgress: (Long, Long) -> Unit): UpdateError? {
        var written = 0L
        val buffer = ByteArray(64 * 1024)
        try {
            part.outputStream().use { out ->
                onProgress(0, update.size)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = source.read(buffer)
                    if (n < 0) break
                    // The body must never be bigger than what the server announced.
                    if (n > update.size - written) {
                        part.delete()
                        return UpdateError.Corrupt
                    }
                    out.write(buffer, 0, n)
                    written += n
                    onProgress(written, update.size)
                }
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            part.delete()
            return UpdateError.Network
        }
        return if (written < update.size) {
            part.delete()
            UpdateError.Network
        } else {
            null
        }
    }

    private fun finish(part: File, apk: File, update: AppUpdateInfo): DownloadOutcome {
        if (!ApkVerifier.matches(part, update)) {
            part.delete()
            return DownloadOutcome.Failure(UpdateError.Corrupt)
        }
        apk.delete()
        if (!part.renameTo(apk)) {
            part.delete()
            return DownloadOutcome.Failure(UpdateError.Network)
        }
        return DownloadOutcome.Success(apk)
    }

    private companion object {
        const val STORAGE_MARGIN = 8L * 1024 * 1024
    }
}

object ApkVerifier {
    /** Size AND SHA-256 (streamed) of [file] against the server's metadata. */
    fun matches(file: File, update: AppUpdateInfo): Boolean =
        file.isFile && file.length() == update.size && sha256Hex(file).equals(update.sha256, ignoreCase = true)

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
