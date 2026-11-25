package xyz.aresius.audiobooks.debug

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.design.AudiobooksTheme
import xyz.aresius.audiobooks.core.torrent.DeviceStateMonitor
import xyz.aresius.audiobooks.core.torrent.EngineActivity
import xyz.aresius.audiobooks.core.torrent.TorrentDownload
import xyz.aresius.audiobooks.core.torrent.TorrentEngine
import xyz.aresius.audiobooks.core.torrent.TorrentFileInfo
import xyz.aresius.audiobooks.core.torrent.TorrentProgress
import xyz.aresius.audiobooks.core.torrent.TorrentRuntime
import xyz.aresius.audiobooks.core.torrent.TorrentSettings
import xyz.aresius.audiobooks.core.torrent.TorrentSettingsStore
import xyz.aresius.audiobooks.core.torrent.formatProgress

data class TorrentDebugState(
    val magnet: String = "",
    val files: List<TorrentFileInfo> = emptyList(),
    /** Selected paths in the order they were ticked: that is the download order. */
    val selected: List<String> = emptyList(),
    val status: String = "",
    val progress: String = "",
    val log: List<String> = emptyList(),
)

/**
 * Debug-only harness for the BitTorrent engine: paste a magnet, list its files, tick the ones you want (tick order = download order),
 * download, watch progress, pause (cancel the collector), resume (download again), remove. The safety policy applies as in the app:
 * with no VPN the engine pauses (Settings > BitTorrent has the switch; here "VPN required" can be toggled too). Public-domain content only.
 * Start: `adb shell am start -n xyz.aresius.audiobooks/.debug.TorrentDebugActivity`
 */
@HiltViewModel
class TorrentDebugViewModel @Inject constructor(
    @ApplicationContext context: android.content.Context,
    private val engine: TorrentEngine,
    private val runtime: TorrentRuntime,
    private val settings: TorrentSettingsStore,
    monitor: DeviceStateMonitor,
) : ViewModel() {
    private val _state = MutableStateFlow(TorrentDebugState())
    val state: StateFlow<TorrentDebugState> = _state
    val activity: StateFlow<EngineActivity> = runtime.activity
    val device = monitor.state
    val torrentSettings = MutableStateFlow(TorrentSettings())
    private val destination = File(context.getExternalFilesDir(null) ?: context.filesDir, "torrent-debug")
    private var job: Job? = null

    init {
        viewModelScope.launch { settings.settings.collect { torrentSettings.value = it } }
    }

    fun setMagnet(m: String) = _state.update { it.copy(magnet = m) }

    private fun hash(): String? =
        Regex("btih:([0-9a-fA-F]{40})").find(_state.value.magnet)?.groupValues?.get(1)?.lowercase()

    fun fetch() {
        val h = hash() ?: return _state.update { it.copy(status = "Magnet needs a 40-hex btih info hash") }
        _state.update { it.copy(status = "Fetching metadata (up to 60 s)…", files = emptyList(), selected = emptyList()) }
        viewModelScope.launch {
            runCatching { engine.fetchFiles(h, _state.value.magnet) }
                .onSuccess { f -> _state.update { it.copy(files = f, status = "${f.size} files") } }
                .onFailure { e -> _state.update { it.copy(status = "Fetch failed: ${e.message}") } }
        }
    }

    fun toggle(path: String) = _state.update { it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path) }

    fun download() {
        val h = hash() ?: return
        val sel = _state.value.selected
        if (sel.isEmpty()) return _state.update { it.copy(status = "Select at least one file") }
        job?.cancel()
        job = viewModelScope.launch {
            engine.download(TorrentDownload(h, _state.value.magnet, sel, destination)).collect { p ->
                _state.update { it.copy(progress = formatProgress(p), log = (listOf(formatProgress(p)) + it.log).distinct().take(30)) }
            }
            _state.update { it.copy(status = "Flow completed") }
        }
        _state.update { it.copy(status = "Downloading to $destination") }
    }

    fun pause() {
        job?.cancel(); job = null
        _state.update { it.copy(status = "Collector cancelled = paused (state is on disk)") }
    }

    fun remove() {
        val h = hash() ?: return
        job?.cancel(); job = null
        viewModelScope.launch {
            engine.remove(h, deleteFiles = true)
            _state.update { it.copy(status = "Removed with files", progress = "") }
        }
    }

    fun setP2p(on: Boolean) = viewModelScope.launch { settings.update { it.copy(p2pEnabled = on) } }

    fun setRequireVpn(on: Boolean) = viewModelScope.launch { settings.update { it.copy(requireVpn = on) } }

    fun setMobile(on: Boolean) = viewModelScope.launch { settings.update { it.copy(allowMobileData = on) } }

    fun setUserPaused(p: Boolean) = runtime.setUserPaused(p)
}

@AndroidEntryPoint
class TorrentDebugActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AudiobooksTheme { Surface(Modifier.fillMaxSize()) { TorrentDebugScreen() } } }
    }
}

@Composable
private fun TorrentDebugScreen(vm: TorrentDebugViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val a by vm.activity.collectAsStateWithLifecycle()
    val d by vm.device.collectAsStateWithLifecycle()
    val cfg by vm.torrentSettings.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("BitTorrent engine (debug)", style = MaterialTheme.typography.titleLarge) }
        item {
            Text(
                "opt-in=${cfg.p2pEnabled} vpnRequired=${cfg.requireVpn} mobileOk=${cfg.allowMobileData}\n" +
                    "device: vpn=${d.vpnActive} net=${d.network} battery=${d.batteryPercent}% charging=${d.charging}\n" +
                    "engine: downloads=${a.downloads} seeding=${a.seeding} paused=${a.paused} ${a.downBytesPerSec / 1024} KiB/s peers=${a.peers}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item { SwitchRow("Allow BitTorrent (local override; the server value wins when signed in)", cfg.p2pEnabled, vm::setP2p) }
        item { SwitchRow("Only with a VPN", cfg.requireVpn, vm::setRequireVpn) }
        item { SwitchRow("Allow mobile data", cfg.allowMobileData, vm::setMobile) }
        item {
            OutlinedTextField(
                value = s.magnet, onValueChange = vm::setMagnet, label = { Text("magnet:?xt=urn:btih:… (public domain, e.g. LibriVox)") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::fetch) { Text("List files") }
                Button(onClick = vm::download) { Text("Download") }
                OutlinedButton(onClick = vm::pause) { Text("Pause") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::remove) { Text("Remove") }
                OutlinedButton(onClick = { vm.setUserPaused(true) }) { Text("Engine pause") }
                OutlinedButton(onClick = { vm.setUserPaused(false) }) { Text("Engine resume") }
            }
        }
        item { Text(s.status, style = MaterialTheme.typography.bodyMedium) }
        item { Text(s.progress, style = MaterialTheme.typography.titleSmall) }
        items(s.files, key = { it.index }) { f ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = f.path in s.selected, onCheckedChange = { vm.toggle(f.path) })
                val order = s.selected.indexOf(f.path).takeIf { it >= 0 }?.let { "#${it + 1} " } ?: ""
                Text("$order${f.path} (${f.size / 1024} KiB)", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("Log", style = MaterialTheme.typography.titleSmall) }
        items(s.log) { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
