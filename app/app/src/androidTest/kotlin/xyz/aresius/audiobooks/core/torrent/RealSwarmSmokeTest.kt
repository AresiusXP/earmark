package xyz.aresius.audiobooks.core.torrent

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume

/**
 * OPTIONAL, MANUAL, OFF BY DEFAULT: one real swarm over the real DHT. Public-domain content only (a LibriVox release). The policy inputs
 * are faked here (the rig says "VPN up"), so nothing protects the emulator's traffic: run it only from a host that sits behind the VPN
 * you would use on the phone, because your IP address is visible in the swarm.
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=xyz.aresius.audiobooks.core.torrent.RealSwarmSmokeTest \
 *     -Pandroid.testInstrumentationRunnerArguments.librivoxMagnet='magnet:?xt=urn:btih:<40 hex>...'
 *
 * Without the `librivoxMagnet` argument the test is skipped.
 */
class RealSwarmSmokeTest {
    @Test fun listsFilesAndDownloadsTheSmallestOne() = runBlocking<Unit> {
        val magnet = InstrumentationRegistry.getArguments().getString("librivoxMagnet")
        Assume.assumeTrue("pass -Pandroid.testInstrumentationRunnerArguments.librivoxMagnet=… to run", !magnet.isNullOrBlank())
        val hash = Regex("btih:([0-9a-fA-F]{40})").find(magnet!!)!!.groupValues[1].lowercase()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "real-smoke").also { it.deleteRecursively(); it.mkdirs() }
        val rig = Rig(dir, EngineConfig(observeProcessLifecycle = false))
        try {
            val files = withTimeout(90_000) { rig.engine.fetchFiles(hash, magnet) }
            assertTrue(files.isNotEmpty())
            val smallest = files.minBy { it.size }
            val events = withTimeout(600_000) { rig.engine.download(TorrentDownload(hash, magnet, listOf(smallest.path), File(dir, "dest"))).toList() }
            assertTrue(events.last() is TorrentProgress.Finished, "last event: ${events.last()}")
        } finally {
            rig.close(); dir.deleteRecursively()
        }
    }
}
