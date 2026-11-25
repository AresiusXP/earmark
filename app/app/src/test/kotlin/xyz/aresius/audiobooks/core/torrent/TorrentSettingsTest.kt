package xyz.aresius.audiobooks.core.torrent

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class TorrentSettingsTest {
    private val dir = Files.createTempDirectory("torrent-settings").toFile()
    private val scopes = mutableListOf<CoroutineScope>()

    /** One DataStore per "process run": [close] cancels its scope, like a process death would. */
    private fun open(server: MutableStateFlow<Boolean?>): DataStoreTorrentSettings {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return DataStoreTorrentSettings(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("s.preferences_pb") }, server, scope)
    }

    private fun close() {
        scopes.forEach { it.cancel() }; scopes.clear()
        Thread.sleep(300) // DataStore releases the file when its scope has finished
    }

    @AfterTest fun cleanup() {
        close(); dir.deleteRecursively()
    }

    @Test fun `defaults are safe`() = runBlocking<Unit> {
        val s = open(MutableStateFlow(null)).settings.first()
        assertEquals(TorrentSettings(), s)
        assertFalse(s.p2pEnabled); assertTrue(s.requireVpn); assertFalse(s.allowMobileData)
    }

    @Test fun `the server opt-in wins and is mirrored for the next cold start`() = runBlocking<Unit> {
        val server = MutableStateFlow<Boolean?>(true)
        val a = open(server)
        assertTrue(a.settings.first().p2pEnabled)
        server.value = false
        withTimeout(5000) { a.settings.first { !it.p2pEnabled } }
        server.value = true
        withTimeout(5000) { a.settings.first { it.p2pEnabled } }
        delay(300)
        close()
        // cold start, the user cache is not loaded yet (null): the mirrored value is used
        assertTrue(open(MutableStateFlow(null)).settings.first().p2pEnabled)
    }

    @Test fun `updates persist and are clamped`() = runBlocking<Unit> {
        open(MutableStateFlow(null)).update {
            it.copy(requireVpn = false, allowMobileData = true, seedToRatio = true, maxConnections = 5000, maxActiveTorrents = 0, downloadLimitKib = -4, uploadLimitKib = 50)
        }
        close()
        val s = open(MutableStateFlow(null)).settings.first()
        assertFalse(s.requireVpn); assertTrue(s.allowMobileData); assertTrue(s.seedToRatio)
        assertEquals(500, s.maxConnections); assertEquals(1, s.maxActiveTorrents); assertEquals(0, s.downloadLimitKib); assertEquals(50, s.uploadLimitKib)
    }
}
