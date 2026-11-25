package xyz.aresius.audiobooks.feature.bittorrent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.feature.common.message

/** The explanation, the "Allow BitTorrent downloads" switch (default OFF) and the confirmation dialog; shared by onboarding and Settings. */
@Composable
fun BitTorrentCard(modifier: Modifier = Modifier, viewModel: BitTorrentViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        BitTorrentSwitchCard(state, viewModel)
        if (state.enabled) BitTorrentOptions()
    }
    BitTorrentWarning(state, viewModel)
}

@Composable
private fun BitTorrentSwitchCard(state: BitTorrentUiState, viewModel: BitTorrentViewModel) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.bittorrent_switch_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(if (state.enabled) R.string.bittorrent_switch_on else R.string.bittorrent_switch_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.enabled, onCheckedChange = viewModel::onSwitch, enabled = !state.saving)
            }
            state.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun BitTorrentWarning(state: BitTorrentUiState, viewModel: BitTorrentViewModel) {
    if (state.warningVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissWarning,
            title = { Text(stringResource(R.string.bittorrent_warning_title)) },
            text = { Text(stringResource(R.string.bittorrent_warning_message)) },
            confirmButton = { TextButton(onClick = viewModel::confirmWarning) { Text(stringResource(R.string.bittorrent_warning_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissWarning) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** The risk, in plain words, shown above the switch in the onboarding step and in Settings. */
@Composable
fun BitTorrentRiskText(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(R.string.bittorrent_explain_1), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.bittorrent_explain_2), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.bittorrent_explain_3), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
