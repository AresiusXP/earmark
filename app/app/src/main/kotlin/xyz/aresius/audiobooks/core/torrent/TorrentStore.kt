package xyz.aresius.audiobooks.core.torrent

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the engine remembers about a download between process runs (next to libtorrent's own fast-resume data). */
@Serializable
data class StoredDownload(val magnet: String, val destination: String, val selectedPaths: List<String>)

/**
 * Engine state on disk below [root] (the app's no-backup dir): the metadata of every torrent whose file list was fetched (a plain
 * .torrent, so a list fetched once never needs the network again), fast-resume data per downloading torrent, the download record, the DHT/session state and the scratch
 * directory of metadata fetches.
 */
class TorrentStore(val root: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val resumeDir get() = File(root, "resume").also { it.mkdirs() }
    private val stateDir get() = File(root, "state").also { it.mkdirs() }
    val sessionState: File get() = File(root, "session.state").also { it.parentFile?.mkdirs() }

    fun resumeFile(hash: String) = File(resumeDir, "$hash.resume")

    fun metaFile(hash: String) = File(File(root, "meta").also { it.mkdirs() }, "$hash.torrent")

    fun fetchDir(hash: String) = File(root, "fetch/$hash")

    fun loadResume(hash: String): ByteArray? = resumeFile(hash).takeIf { it.isFile }?.readBytes()

    fun saveResume(hash: String, bytes: ByteArray) {
        val target = resumeFile(hash)
        val tmp = File(target.parentFile, "$hash.resume.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun loadMeta(hash: String): ByteArray? = metaFile(hash).takeIf { it.isFile }?.readBytes()

    fun saveMeta(hash: String, bytes: ByteArray) {
        val tmp = File(metaFile(hash).parentFile, "$hash.torrent.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(metaFile(hash))) { metaFile(hash).delete(); tmp.renameTo(metaFile(hash)) }
    }

    fun saveDownload(hash: String, record: StoredDownload) {
        File(stateDir, "$hash.json").writeText(json.encodeToString(StoredDownload.serializer(), record))
    }

    fun loadDownload(hash: String): StoredDownload? =
        File(stateDir, "$hash.json").takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString(StoredDownload.serializer(), it.readText()) }.getOrNull() }

    fun forget(hash: String) {
        resumeFile(hash).delete()
        metaFile(hash).delete()
        File(stateDir, "$hash.json").delete()
        fetchDir(hash).deleteRecursively()
    }
}
