package xyz.aresius.audiobooks.core.torrent

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.libtorrent4j.TorrentInfo

/**
 * Real libtorrent, no network: a torrent generated at test time is seeded by a second session on 127.0.0.1 and the engine
 * under test (with fake policy inputs) downloads from it through the `x.pe` peer hint of the magnet link.
 */
class LibTorrentEngineTest {
    private lateinit var dir: File
    private lateinit var rig: Rig
    private var swarm: TestSwarm? = null

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("engine-test").toFile()
        rig = Rig(File(dir, "rig").also { it.mkdirs() })
    }

    @AfterTest fun tearDown() {
        rig.close(); swarm?.stop(); dir.deleteRecursively()
    }

    private fun newSwarm(files: Map<String, Int>, keepBytes: Int? = null): TestSwarm =
        TestSwarm.create(File(dir, "seed").also { it.mkdirs() }, files, keepBytes).also { swarm = it }

    private val threeChapters = linkedMapOf("ch1.mp3" to 200_000, "ch2.mp3" to 300_000, "ch3.mp3" to 250_000, "cover.jpg" to 40_000)

    private fun request(s: TestSwarm, paths: List<String>, dest: File) = TorrentDownload(s.infoHash, s.magnet, paths, dest)

    @Test fun fetchFilesListsMetainfoOrderAndIsCached() = runBlocking<Unit> {
        val s = newSwarm(threeChapters)
        val files = withTimeout(60_000) { rig.engine.fetchFiles(s.infoHash, s.magnet) }
        assertEquals(listOf("Book/ch1.mp3", "Book/ch2.mp3", "Book/ch3.mp3", "Book/cover.jpg"), files.map { it.path })
        assertEquals(listOf(200_000L, 300_000L, 250_000L, 40_000L), files.map { it.size })
        // indexes are metainfo positions: BEP 47 pad files take a position but are not listed
        assertTrue(files.map { it.index }.zipWithNext().all { (a, b) -> a < b })
        // the info dictionary is in the fast-resume file now: answering again needs no peers at all
        s.stop(); swarm = null
        assertEquals(files, withTimeout(5_000) { rig.engine.fetchFiles(s.infoHash, s.magnet) })
    }

    @Test fun fetchFilesTimesOutWithoutPeers() = runBlocking<Unit> {
        val short = Rig(File(dir, "short").also { it.mkdirs() }, Rig.testConfig().copy(metadataTimeoutMs = 2_000))
        try {
            val hash = "1".repeat(40)
            assertFailsWith<java.util.concurrent.TimeoutException> { short.engine.fetchFiles(hash, "magnet:?xt=urn:btih:$hash") }
        } finally { short.close() }
    }

    @Test fun selectedFilesOnlyAreDownloadedInOrderAndUnselectedAreNeverCreated() = runBlocking<Unit> {
        val s = newSwarm(threeChapters)
        val dest = File(dir, "dest")
        val events = withTimeout(120_000) {
            rig.engine.download(request(s, listOf("Book/ch2.mp3", "Book/ch1.mp3"), dest)).toList()
        }
        val finished = assertIs<TorrentProgress.Finished>(events.last())
        assertEquals(setOf("Book/ch1.mp3", "Book/ch2.mp3"), finished.completedPaths)
        assertContentEquals(s.files.getValue("Book/ch1.mp3"), File(dest, "Book/ch1.mp3").readBytes())
        assertContentEquals(s.files.getValue("Book/ch2.mp3"), File(dest, "Book/ch2.mp3").readBytes())
        assertFalse(File(dest, "Book/ch3.mp3").exists(), "unselected file must never be created")
        assertFalse(File(dest, "Book/cover.jpg").exists(), "unselected file must never be created")
        // nothing but the selected files (and libtorrent's part file for pieces shared with unselected files) below the destination
        val created = dest.walkTopDown().filter { it.isFile }.map { it.relativeTo(dest).path }.toSet()
        assertTrue(created.all { it.startsWith("Book/ch") || it.endsWith(".parts") }, "unexpected files: $created")
        assertTrue(events.filterIsInstance<TorrentProgress.Downloading>().all { it.bytesTotal == 500_000L })
        // stop seeding by default: nothing stays in the session after Finished
        assertFalse(rig.engine.isInSession(s.infoHash))
        assertEquals(0, rig.engine.activity.value.seeding)
    }

    @Test fun vpnDropPausesAtOnceAndResumeContinues() = runBlocking<Unit> {
        val size = 3_000_000
        val s = newSwarm(linkedMapOf("big.m4b" to size), keepBytes = 1_000_000)
        val dest = File(dir, "dest")
        val events = java.util.concurrent.CopyOnWriteArrayList<TorrentProgress>()
        val job = launch { rig.engine.download(request(s, listOf("Book/big.m4b"), dest)).collect { events += it } }
        // the seeder only has the first third: the download gets there and waits for the rest
        withTimeout(60_000) { while (events.none { it is TorrentProgress.Downloading && it.bytesDone >= 900_000 }) kotlinx.coroutines.delay(50) }
        rig.device.value = rig.device.value.copy(vpnActive = false)
        withTimeout(5_000) { while (!rig.engine.isSessionPaused) kotlinx.coroutines.delay(10) }
        withTimeout(5_000) { while (events.lastOrNull() != TorrentProgress.Paused(PauseReason.VPN_REQUIRED)) kotlinx.coroutines.delay(20) }
        val frozen = events.filterIsInstance<TorrentProgress.Downloading>().last().bytesDone
        // the rest of the data becomes available while the VPN is down: nothing may be fetched
        s.complete()
        kotlinx.coroutines.delay(2_500)
        assertEquals(TorrentProgress.Paused(PauseReason.VPN_REQUIRED), events.last())
        assertTrue(events.none { it is TorrentProgress.Downloading && it.bytesDone > frozen + 16_384 * 2 }, "data flowed while the VPN was down")
        rig.device.value = rig.device.value.copy(vpnActive = true)
        withTimeout(60_000) { job.join() }
        val finished = assertIs<TorrentProgress.Finished>(events.last())
        assertEquals(setOf("Book/big.m4b"), finished.completedPaths)
        assertContentEquals(s.files.getValue("Book/big.m4b"), File(dest, "Book/big.m4b").readBytes())
        assertTrue(frozen in 900_000 until size)
    }

    /** A session that starts while the policy blocks (VPN down) must come up paused with no networking switched on. */
    @Test fun aSessionStartedWhileBlockedIsPausedAndSilent() = runBlocking<Unit> {
        val s = newSwarm(threeChapters)
        rig.device.value = rig.device.value.copy(vpnActive = false)
        withTimeout(5_000) { while (rig.engine.decision != PolicyDecision.Blocked(PauseReason.VPN_REQUIRED)) kotlinx.coroutines.delay(10) }
        val events = java.util.concurrent.CopyOnWriteArrayList<TorrentProgress>()
        val job = launch { rig.engine.download(request(s, listOf("Book/ch1.mp3"), File(dir, "dest-blocked"))).collect { events += it } }
        withTimeout(10_000) { while (events.lastOrNull() != TorrentProgress.Paused(PauseReason.VPN_REQUIRED)) kotlinx.coroutines.delay(20) }
        assertTrue(rig.engine.isSessionPaused)
        assertFalse(rig.engine.networkSwitches()!!.anyEnabled, "no DHT, LSD, port mapping, incoming connection or listen socket while blocked")
        kotlinx.coroutines.delay(1_000)
        assertTrue(events.none { it is TorrentProgress.Downloading || it is TorrentProgress.Starting && it.peers > 0 })
        rig.device.value = rig.device.value.copy(vpnActive = true)
        withTimeout(60_000) { job.join() }
        assertIs<TorrentProgress.Finished>(events.last())
    }

    /** The policy flips while the metadata is being fetched: the fetch ends blocked, the torrent is gone and nothing stays switched on. */
    @Test fun aPolicyFlipDuringMetadataFetchLeavesNothingRunning() = runBlocking<Unit> {
        val hash = "2".repeat(40)
        val fetch = async { runCatching { rig.engine.fetchFiles(hash, "magnet:?xt=urn:btih:$hash") } }
        withTimeout(10_000) { while (!rig.engine.isInSession(hash)) kotlinx.coroutines.delay(10) }
        rig.device.value = rig.device.value.copy(vpnActive = false)
        val failure = withTimeout(10_000) { fetch.await() }.exceptionOrNull()
        assertIs<TorrentBlockedException>(failure)
        assertTrue(rig.engine.isSessionPaused)
        assertFalse(rig.engine.isInSession(hash))
        assertFalse(rig.engine.networkSwitches()!!.anyEnabled)
    }

    /** The system refuses the foreground service: nothing may run unprotected; it waits (Paused(SYSTEM)) and goes on when the service can start. */
    @Test fun aRefusedForegroundServicePausesEverythingUntilItCanStart() = runBlocking<Unit> {
        val allowed = java.util.concurrent.atomic.AtomicBoolean(false)
        val refusing = Rig(File(dir, "refusing").also { it.mkdirs() }, launcher = ServiceLauncher { if (!allowed.get()) throw IllegalStateException("ForegroundServiceStartNotAllowedException") })
        try {
            val s = newSwarm(threeChapters)
            val events = java.util.concurrent.CopyOnWriteArrayList<TorrentProgress>()
            val job = launch { refusing.engine.download(request(s, listOf("Book/ch1.mp3"), File(dir, "dest-refused"))).collect { events += it } }
            withTimeout(10_000) { while (events.lastOrNull() != TorrentProgress.Paused(PauseReason.SYSTEM)) kotlinx.coroutines.delay(20) }
            assertEquals(PauseReason.SYSTEM, refusing.engine.activity.value.paused)
            assertTrue(refusing.engine.suspendedForTimeLimit, "the resume worker picks this up")
            assertTrue(refusing.engine.isSessionPaused)
            kotlinx.coroutines.delay(1_000)
            assertTrue(events.none { it is TorrentProgress.Downloading })
            allowed.set(true)
            refusing.engine.resumeAfterTimeLimit()
            withTimeout(60_000) { job.join() }
            assertIs<TorrentProgress.Finished>(events.last())
        } finally { refusing.close() }
    }

    @Test fun cancellingPausesAndALaterCollectResumesFromDisk() = runBlocking<Unit> {
        val s = newSwarm(linkedMapOf("big.m4b" to 3_000_000), keepBytes = 1_200_000)
        val dest = File(dir, "dest")
        var before = 0L
        withTimeout(60_000) {
            rig.engine.download(request(s, listOf("Book/big.m4b"), dest)).takeWhile {
                if (it is TorrentProgress.Downloading) before = it.bytesDone
                before < 1_100_000
            }.toList()
        }
        assertTrue(before >= 1_100_000, "should have progressed before the collector was cancelled")
        assertTrue(rig.store.resumeFile(s.infoHash).isFile, "fast-resume data is saved when collecting stops")
        assertFalse(rig.engine.isInSession(s.infoHash), "a cancelled collector leaves nothing running")
        s.complete()
        val events = withTimeout(120_000) { rig.engine.download(request(s, listOf("Book/big.m4b"), dest)).toList() }
        val firstDownloading = events.filterIsInstance<TorrentProgress.Downloading>().firstOrNull()
        assertTrue(firstDownloading == null || firstDownloading.bytesDone >= before * 9 / 10, "resume should start from the pieces already on disk (had $before, restarted at ${firstDownloading?.bytesDone})")
        assertIs<TorrentProgress.Finished>(events.last())
        assertContentEquals(s.files.getValue("Book/big.m4b"), File(dest, "Book/big.m4b").readBytes())
    }

    @Test fun seedingIsOffByDefaultAndOptInSeedsOnlyOnWifiWhileCharging() = runBlocking<Unit> {
        val s = newSwarm(threeChapters)
        rig.settings.value = rig.settings.value.copy(seedToRatio = true)
        rig.device.value = rig.device.value.copy(charging = true)
        withTimeout(120_000) { rig.engine.download(request(s, listOf("Book/ch1.mp3"), File(dir, "dest"))).toList() }
        assertTrue(rig.engine.isInSession(s.infoHash), "seeding was opted in, on Wi-Fi, charging")
        assertEquals(1, rig.engine.activity.value.seeding)
        rig.device.value = rig.device.value.copy(charging = false)
        withTimeout(20_000) { while (rig.engine.isInSession(s.infoHash)) kotlinx.coroutines.delay(200) }
        assertEquals(0, rig.engine.activity.value.seeding)
    }

    @Test fun refusesWithoutTheOptInInsideTheEngine() = runBlocking<Unit> {
        val s = newSwarm(threeChapters)
        rig.settings.value = rig.settings.value.copy(p2pEnabled = false)
        val e = assertFailsWith<TorrentBlockedException> { rig.engine.fetchFiles(s.infoHash, s.magnet) }
        assertEquals(PolicyDecision.Disabled, e.decision)
        val events = withTimeout(10_000) { rig.engine.download(request(s, listOf("Book/ch1.mp3"), File(dir, "dest"))).toList() }
        assertIs<TorrentProgress.Failed>(events.single())
        assertFalse(File(dir, "dest/Book").exists())
    }

    @Test fun fetchIsRefusedWithoutVpnAndMobileDataIsOffByDefault() = runBlocking<Unit> {
        val s = newSwarm(threeChapters)
        rig.device.value = rig.device.value.copy(vpnActive = false)
        withTimeout(5_000) { while (rig.engine.decision != PolicyDecision.Blocked(PauseReason.VPN_REQUIRED)) kotlinx.coroutines.delay(10) }
        assertEquals(PolicyDecision.Blocked(PauseReason.VPN_REQUIRED), assertFailsWith<TorrentBlockedException> { rig.engine.fetchFiles(s.infoHash, s.magnet) }.decision)
        rig.device.value = rig.device.value.copy(vpnActive = true, network = NetworkKind.MOBILE)
        withTimeout(5_000) { while (rig.engine.decision != PolicyDecision.Blocked(PauseReason.WIFI_REQUIRED)) kotlinx.coroutines.delay(10) }
        assertEquals(PolicyDecision.Blocked(PauseReason.WIFI_REQUIRED), assertFailsWith<TorrentBlockedException> { rig.engine.fetchFiles(s.infoHash, s.magnet) }.decision)
    }

    /** The configuration the app really uses (wildcard listen interfaces, DHT/LSD/port mapping on) can reach a peer too. */
    @Test fun productionSessionSettingsWork() = runBlocking<Unit> {
        val prod = Rig(File(dir, "prod").also { it.mkdirs() }, EngineConfig(observeProcessLifecycle = false, idleStopMs = 300))
        try {
            val s = newSwarm(threeChapters)
            val events = withTimeout(120_000) { prod.engine.download(request(s, listOf("Book/ch3.mp3"), File(dir, "dest-prod"))).toList() }
            assertIs<TorrentProgress.Finished>(events.last())
            assertContentEquals(s.files.getValue("Book/ch3.mp3"), File(dir, "dest-prod/Book/ch3.mp3").readBytes())
        } finally { prod.close() }
    }

    @Test fun maliciousPathsNeverLeaveTheDestination() = runBlocking<Unit> {
        val evil = ByteArray(40_000) { (it * 31).toByte() }
        val good = ByteArray(40_000) { (it * 17).toByte() }
        val third = ByteArray(40_000) { (it * 13).toByte() }
        val bytes = Bencode.torrent(
            "Evil",
            listOf(
                listOf("..", "..", "evil.txt") to evil,
                listOf("/", "etc", "passwd") to third,
                listOf("good.mp3") to good,
            ),
        )
        val info = TorrentInfo(bytes)
        // data at the paths libtorrent assigned to the files (that is what a seeder holds)
        val seedRoot = File(dir, "evil-seed").also { it.mkdirs() }
        val content = HashMap<String, ByteArray>()
        val datas = listOf(evil, third, good)
        for (i in 0 until info.numFiles()) {
            val p = info.files().filePath(i)
            File(seedRoot, p).also { it.parentFile!!.mkdirs() }.writeBytes(datas[i])
            content[p] = datas[i]
        }
        val s = TestSwarm.seedFrom(seedRoot, info, content).also { swarm = it }
        val files = withTimeout(60_000) { rig.engine.fetchFiles(s.infoHash, s.magnet) }
        assertTrue(files.isNotEmpty())
        files.forEach { assertTrue(TorrentPaths.safeRelative(it.path) != null, "unsafe path reported: ${it.path}") }
        assertFalse(files.any { it.path.contains("..") || it.path.startsWith("/") })

        // asking for a path that is not in the list (a raw traversal) is refused before anything is added
        val dest = File(dir, "jail/dest").also { it.mkdirs() }
        val bad = withTimeout(10_000) { rig.engine.download(request(s, listOf("../../evil.txt"), dest)).toList() }
        assertIs<TorrentProgress.Failed>(bad.single())

        val events = withTimeout(120_000) { rig.engine.download(request(s, files.map { it.path }, dest)).toList() }
        assertIs<TorrentProgress.Finished>(events.last())
        // nothing was written anywhere but below the destination
        val jail = File(dir, "jail")
        val outside = jail.walkTopDown().filter { it.isFile && !it.canonicalPath.startsWith(dest.canonicalPath + File.separator) }.toList()
        assertEquals(emptyList(), outside)
        assertFalse(File(jail, "evil.txt").exists())
        assertFalse(File(dir, "evil.txt").exists())
        assertFalse(File("/etc/passwd").let { it.exists() && it.lastModified() > System.currentTimeMillis() - 60_000 })
    }

    @Test fun removeDeletesDownloadedFilesAndState() = runBlocking<Unit> {
        val s = newSwarm(threeChapters)
        val dest = File(dir, "dest")
        withTimeout(120_000) { rig.engine.download(request(s, listOf("Book/ch1.mp3"), dest)).toList() }
        assertTrue(File(dest, "Book/ch1.mp3").isFile)
        rig.engine.remove(s.infoHash, deleteFiles = true)
        assertFalse(File(dest, "Book/ch1.mp3").exists())
        assertFalse(rig.store.resumeFile(s.infoHash).exists())
    }
}
