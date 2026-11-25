package xyz.aresius.audiobooks.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.PillChip
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.player.PlaybackRules
import xyz.aresius.audiobooks.core.player.PlayerPreferences

data class PlaybackSettingsState(val skipBack: Int = PlaybackRules.DEFAULT_SKIP_S, val skipForward: Int = PlaybackRules.DEFAULT_SKIP_S, val smartRewind: Boolean = true)

@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(private val prefs: PlayerPreferences) : ViewModel() {
    val state: StateFlow<PlaybackSettingsState> = combine(prefs.skipBackSeconds, prefs.skipForwardSeconds, prefs.smartRewind, ::PlaybackSettingsState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackSettingsState())

    fun setSkipBack(seconds: Int) {
        viewModelScope.launch { prefs.setSkipBackSeconds(seconds) }
    }

    fun setSkipForward(seconds: Int) {
        viewModelScope.launch { prefs.setSkipForwardSeconds(seconds) }
    }

    fun setSmartRewind(enabled: Boolean) {
        viewModelScope.launch { prefs.setSmartRewind(enabled) }
    }
}

/** Settings > Playback: the skip lengths (10/15/30/45/60 s) and smart rewind. */
@Composable
fun PlaybackSection(viewModel: PlaybackSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(R.string.player_pref_skip_back), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            PlaybackRules.SKIP_OPTIONS_S.forEach { s ->
                PillChip(stringResource(R.string.player_pref_seconds, s), selected = s == state.skipBack, onClick = { viewModel.setSkipBack(s) })
            }
        }
        Text(stringResource(R.string.player_pref_skip_forward), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            PlaybackRules.SKIP_OPTIONS_S.forEach { s ->
                PillChip(stringResource(R.string.player_pref_seconds, s), selected = s == state.skipForward, onClick = { viewModel.setSkipForward(s) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.player_pref_smart_rewind), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.player_pref_smart_rewind_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = state.smartRewind, onCheckedChange = viewModel::setSmartRewind)
        }
    }
}
