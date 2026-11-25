package xyz.aresius.audiobooks.feature.bittorrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import xyz.aresius.audiobooks.core.torrent.TorrentSettings
import xyz.aresius.audiobooks.core.torrent.TorrentSettingsStore
import xyz.aresius.audiobooks.testutil.MainDispatcherRule
import xyz.aresius.audiobooks.testutil.awaitState

private class FakeStore : TorrentSettingsStore {
    val state = MutableStateFlow(TorrentSettings())
    override val settings: Flow<TorrentSettings> = state
    override suspend fun update(transform: (TorrentSettings) -> TorrentSettings) { state.value = transform(state.value) }
}

class BitTorrentOptionsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `safe defaults are shown and every option writes through to the store`() = kotlinx.coroutines.runBlocking<Unit> {
        val store = FakeStore()
        val vm = BitTorrentOptionsViewModel(store)
        val s = vm.settings.awaitState { true }
        assertTrue(s.requireVpn); assertFalse(s.allowMobileData); assertFalse(s.seedToRatio)

        vm.setRequireVpn(false); vm.setAllowMobileData(true); vm.setSeedToRatio(true)
        vm.setDownloadLimitKib(512); vm.setUploadLimitKib(64); vm.setMaxConnections(1000); vm.setMaxActive(9)
        val now = vm.settings.awaitState { it.seedToRatio }
        assertFalse(now.requireVpn); assertTrue(now.allowMobileData)
        assertEquals(512, now.downloadLimitKib); assertEquals(64, now.uploadLimitKib)
        assertEquals(500, now.maxConnections); assertEquals(5, now.maxActiveTorrents)
        assertFalse(now.p2pEnabled, "the opt-in is not an option of this screen")
    }
}
