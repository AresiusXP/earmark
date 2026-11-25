package xyz.aresius.audiobooks.feature.bittorrent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.torrent.TorrentSettings
import xyz.aresius.audiobooks.core.torrent.TorrentSettingsStore

/** The local BitTorrent safety options (DataStore). The opt-in itself stays in [BitTorrentViewModel] (server setting). */
@HiltViewModel
class BitTorrentOptionsViewModel @Inject constructor(private val store: TorrentSettingsStore) : ViewModel() {
    val settings: StateFlow<TorrentSettings> = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, TorrentSettings())

    fun setRequireVpn(value: Boolean) = edit { it.copy(requireVpn = value) }

    fun setAllowMobileData(value: Boolean) = edit { it.copy(allowMobileData = value) }

    fun setSeedToRatio(value: Boolean) = edit { it.copy(seedToRatio = value) }

    fun setDownloadLimitKib(value: Int) = edit { it.copy(downloadLimitKib = value.coerceAtLeast(0)) }

    fun setUploadLimitKib(value: Int) = edit { it.copy(uploadLimitKib = value.coerceAtLeast(0)) }

    fun setMaxConnections(value: Int) = edit { it.copy(maxConnections = value.coerceIn(10, 500)) }

    fun setMaxActive(value: Int) = edit { it.copy(maxActiveTorrents = value.coerceIn(1, 5)) }

    private fun edit(transform: (TorrentSettings) -> TorrentSettings) {
        viewModelScope.launch { store.update(transform) }
    }
}

/** VPN requirement, mobile data, seeding and the caps. Shown below the opt-in switch once BitTorrent is allowed. */
@Composable
fun BitTorrentOptions(modifier: Modifier = Modifier, viewModel: BitTorrentOptionsViewModel = hiltViewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    var confirmVpnOff by remember { mutableStateOf(false) }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(stringResource(R.string.torrent_options_title), style = MaterialTheme.typography.titleMedium)
            OptionSwitch(R.string.torrent_vpn_title, R.string.torrent_vpn_desc, s.requireVpn) {
                if (it) viewModel.setRequireVpn(true) else confirmVpnOff = true
            }
            OptionSwitch(R.string.torrent_mobile_title, R.string.torrent_mobile_desc, s.allowMobileData, viewModel::setAllowMobileData)
            OptionSwitch(R.string.torrent_seed_title, R.string.torrent_seed_desc, s.seedToRatio, viewModel::setSeedToRatio)
            Text(stringResource(R.string.torrent_limits_title), style = MaterialTheme.typography.titleSmall)
            NumberField(R.string.torrent_down_limit, s.downloadLimitKib, viewModel::setDownloadLimitKib)
            NumberField(R.string.torrent_up_limit, s.uploadLimitKib, viewModel::setUploadLimitKib)
            NumberField(R.string.torrent_max_connections, s.maxConnections, viewModel::setMaxConnections)
            NumberField(R.string.torrent_max_active, s.maxActiveTorrents, viewModel::setMaxActive)
        }
    }
    if (confirmVpnOff) {
        AlertDialog(
            onDismissRequest = { confirmVpnOff = false },
            title = { Text(stringResource(R.string.torrent_vpn_off_title)) },
            text = { Text(stringResource(R.string.torrent_vpn_off_message)) },
            confirmButton = {
                TextButton(onClick = { confirmVpnOff = false; viewModel.setRequireVpn(false) }) { Text(stringResource(R.string.torrent_vpn_off_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmVpnOff = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun OptionSwitch(title: Int, description: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: Int, value: Int, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            val digits = new.filter { it.isDigit() }.take(6)
            text = digits
            onValue(digits.toIntOrNull() ?: 0)
        },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
