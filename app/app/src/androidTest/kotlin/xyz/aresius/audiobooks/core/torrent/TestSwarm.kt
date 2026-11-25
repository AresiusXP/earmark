package xyz.aresius.audiobooks.core.torrent

import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.swig.torrent_flags_t
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.settings_pack

/** A torrent made at test time from random bytes, seeded by a second libtorrent session on the loopback (no network, no trackers, no DHT). */
class TestSwarm private constructor(
    val root: File,
    val torrent: TorrentInfo,
    val files: Map<String, ByteArray>,
    private val seeder: SessionManager,
    val port: Int,
) {
    val infoHash: String = torrent.infoHash().toHex()
    val magnet: String get() = "magnet:?xt=urn:btih:$infoHash&dn=Test&x.pe=127.0.0.1:$port"

    fun stop() = seeder.stop()

    /** For a swarm created with [create]'s `keepBytes`: puts the real data in place and re-checks, so the seeder now has every piece. */
    fun complete() {
        files.forEach { (name, bytes) -> File(root, name).writeBytes(bytes) }
        val h = seeder.find(Sha1Hash.parseHex(infoHash))!!
        h.forceRecheck()
        val deadline = System.currentTimeMillis() + 10_000
        while (!h.status(true).isSeeding) {
            check(System.currentTimeMillis() < deadline) { "seeder did not finish re-checking" }
            Thread.sleep(50)
        }
    }

    companion object {
        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        /**
         * [files]: paths below a top directory "Book" -> size. With [keepBytes] the seeder holds only the first [keepBytes] bytes of
         * every file (zeros after that) until [complete] is called, which makes "mid-download" deterministic: loopback transfers
         * are not rate-limited by libtorrent.
         */
        fun create(workDir: File, files: Map<String, Int>, keepBytes: Int? = null, seed: Int = 7): TestSwarm {
            val random = Random(seed)
            val content = files.mapValues { (_, size) -> random.nextBytes(size) }
            val book = File(workDir, "Book").also { it.mkdirs() }
            content.forEach { (name, bytes) -> File(book, name).also { it.parentFile!!.mkdirs() }.writeBytes(bytes) }
            val entry = TorrentBuilder().path(book).pieceSize(16 * 1024).generate().entry()
            if (keepBytes != null) content.forEach { (name, bytes) -> File(book, name).writeBytes(ByteArray(bytes.size) { if (it < keepBytes) bytes[it] else 0 }) }
            return seedFrom(workDir, TorrentInfo(entry.bencode()), content.mapKeys { "Book/${it.key}" }, waitSeeding = keepBytes == null)
        }

        /** Seeds [info] whose files already sit below [root] at their libtorrent paths. */
        fun seedFrom(root: File, info: TorrentInfo, content: Map<String, ByteArray>, waitSeeding: Boolean = true): TestSwarm {
            val port = freePort()
            val sp = SettingsPack()
            sp.listenInterfaces("127.0.0.1:$port")
            sp.setEnableDht(false)
            sp.setEnableLsd(false)
            sp.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
            sp.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
            sp.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), false)
            sp.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), false)
            val sm = SessionManager()
            // the listen socket opens asynchronously: a connect before that is refused and libtorrent backs off for a long time
            val listening = CountDownLatch(1)
            sm.addListener(object : AlertListener {
                override fun types() = intArrayOf(AlertType.LISTEN_SUCCEEDED.swig())
                override fun alert(alert: Alert<*>) = listening.countDown()
            })
            sm.start(SessionParams(sp))
            check(listening.await(10, TimeUnit.SECONDS)) { "seeder is not listening" }
            val deadline = System.currentTimeMillis() + 10_000
            // a normal add: libtorrent checks the files once, then seeds (seed mode would re-check on the first request and drop the peer)
            sm.download(info, root, null, null, null, torrent_flags_t())
            var handle = sm.find(Sha1Hash.parseHex(info.infoHash().toHex()))
            while (handle == null) {
                check(System.currentTimeMillis() < deadline) { "seeder did not add the torrent" }
                Thread.sleep(50)
                handle = sm.find(Sha1Hash.parseHex(info.infoHash().toHex()))
            }
            // not auto-managed: a queued torrent refuses peers until the next auto-manage tick
            handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
            handle.resume()
            while (handle.status(true).let { if (waitSeeding) !it.isSeeding else it.state() == TorrentStatus.State.CHECKING_FILES || !it.hasMetadata() }) {
                check(System.currentTimeMillis() < deadline) { "seeder is not ready" }
                Thread.sleep(50)
            }
            return TestSwarm(root, info, content, sm, port)
        }
    }
}

/** Minimal bencoder for hand-made (malicious) torrents. Dictionary keys are sorted as the format requires. */
object Bencode {
    fun encode(v: Any): ByteArray = when (v) {
        is Long -> "i${v}e".toByteArray()
        is Int -> "i${v}e".toByteArray()
        is String -> encode(v.toByteArray())
        is ByteArray -> "${v.size}:".toByteArray() + v
        is List<*> -> "l".toByteArray() + v.fold(ByteArray(0)) { acc, e -> acc + encode(e!!) } + "e".toByteArray()
        is Map<*, *> -> "d".toByteArray() +
            v.entries.sortedBy { it.key as String }.fold(ByteArray(0)) { acc, e -> acc + encode(e.key as String) + encode(e.value!!) } + "e".toByteArray()
        else -> error("cannot bencode ${v::class}")
    }

    /** A multi-file torrent whose file entries carry the given raw path components (which libtorrent must sanitize). */
    fun torrent(name: String, entries: List<Pair<List<String>, ByteArray>>, pieceLength: Int = 16 * 1024): ByteArray {
        val all = entries.fold(ByteArray(0)) { acc, e -> acc + e.second }
        val pieces = all.toList().chunked(pieceLength).fold(ByteArray(0)) { acc, chunk ->
            acc + MessageDigest.getInstance("SHA-1").digest(chunk.toByteArray())
        }
        val info = mapOf(
            "name" to name,
            "piece length" to pieceLength,
            "pieces" to pieces,
            "files" to entries.map { (path, data) -> mapOf("length" to data.size, "path" to path) },
        )
        return encode(mapOf("info" to info))
    }
}

/** Fakes for the engine's inputs, driven by the tests. */
class Rig(val dir: File, config: EngineConfig = testConfig()) {
    val settings = MutableStateFlow(TorrentSettings(p2pEnabled = true))
    val device = MutableStateFlow(DeviceState(vpnActive = true, network = NetworkKind.WIFI, batteryPercent = 80, charging = false))
    val store = TorrentStore(File(dir, "engine"))
    val engine = LibTorrentEngine(
        object : TorrentSettingsSource { override val settings = this@Rig.settings },
        object : DeviceStateMonitor { override val state = device },
        store,
        ServiceLauncher { },
        config,
    )

    fun close() = engine.shutdown()

    companion object {
        fun testConfig() = EngineConfig(
            listenInterfaces = "127.0.0.1:0", enableDht = false, enableLsd = false, enableUtp = false, enablePortMaps = false,
            metadataTimeoutMs = 30_000, idleStopMs = 300, resumeSaveEveryMs = 1_000, tickMs = 200, observeProcessLifecycle = false,
        )
    }
}
