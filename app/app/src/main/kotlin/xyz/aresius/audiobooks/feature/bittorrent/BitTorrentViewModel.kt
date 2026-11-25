package xyz.aresius.audiobooks.feature.bittorrent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.data.MeRepository
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult

data class BitTorrentUiState(
    /** The server's `settings.p2p_enabled` (false until the user turned it on after the warning). */
    val enabled: Boolean = false,
    /** The warning is on screen: the switch only turns on after the user accepts it. */
    val warningVisible: Boolean = false,
    val saving: Boolean = false,
    val error: ApiError? = null,
)

/**
 * The "Allow BitTorrent downloads" switch (onboarding step and Settings). OFF by default; turning it ON needs an explicit
 * confirmation of the risk (the user's IP address is visible in the swarm), turning it OFF is immediate. The server stores
 * `p2p_enabled` + a coherent `source_mode` ([xyz.aresius.audiobooks.core.data.P2pSettings]); the rules are enforced in the app.
 */
@HiltViewModel
class BitTorrentViewModel @Inject constructor(
    private val me: MeRepository,
) : ViewModel() {
    private val local = MutableStateFlow(BitTorrentUiState())

    val state: StateFlow<BitTorrentUiState> = combine(me.me, local) { user, l ->
        l.copy(enabled = user?.settings?.p2pEnabled ?: false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BitTorrentUiState())

    fun onSwitch(checked: Boolean) {
        if (local.value.saving) return
        if (checked) local.update { it.copy(warningVisible = true, error = null) } else save(false)
    }

    fun confirmWarning() {
        local.update { it.copy(warningVisible = false) }
        save(true)
    }

    fun dismissWarning() = local.update { it.copy(warningVisible = false) }

    fun consumeError() = local.update { it.copy(error = null) }

    private fun save(enabled: Boolean) {
        local.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val r = me.setP2p(enabled)
            local.update { it.copy(saving = false, error = (r as? ApiResult.Failure)?.error) }
        }
    }
}
