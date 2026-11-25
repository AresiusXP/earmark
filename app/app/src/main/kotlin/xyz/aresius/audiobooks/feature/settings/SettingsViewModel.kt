package xyz.aresius.audiobooks.feature.settings

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
import xyz.aresius.audiobooks.core.auth.AuthRepository
import xyz.aresius.audiobooks.core.auth.BuildInfo
import xyz.aresius.audiobooks.core.auth.InputProblem
import xyz.aresius.audiobooks.core.auth.InputValidation
import xyz.aresius.audiobooks.core.data.AppPreferences
import xyz.aresius.audiobooks.core.data.MeRepository
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.Device
import xyz.aresius.audiobooks.core.network.LinkCodeResponse
import xyz.aresius.audiobooks.core.network.Me
import xyz.aresius.audiobooks.core.network.SETTINGS_FORMATS
import xyz.aresius.audiobooks.core.network.SETTINGS_LANGUAGES
import xyz.aresius.audiobooks.core.network.SETTINGS_REGIONS
import xyz.aresius.audiobooks.core.network.SettingsPatch
import xyz.aresius.audiobooks.core.network.SourceMode
import xyz.aresius.audiobooks.core.network.UpdateMeRequest
import xyz.aresius.audiobooks.feature.common.Load

data class SettingsUiState(
    val me: Me? = null,
    val devices: Load<List<Device>> = Load.Loading,
    /** A settings write is in flight (the controls stay as they were until it succeeds). */
    val saving: Boolean = false,
    val busyDeviceId: String? = null,
    val linkCode: LinkCodeResponse? = null,
    val creatingLinkCode: Boolean = false,
    /** Set when the display name entered in the rename dialog was refused locally. */
    val nameProblem: InputProblem? = null,
    val error: ApiError? = null,
    val versionName: String = "",
    /** Opt-in wallpaper colors (Android 12+); the app's own palette otherwise. */
    val dynamicColor: Boolean = false,
) {
    /** "BitTorrent even with a key" only makes sense once the user allowed BitTorrent. */
    val p2pSourceModeAvailable: Boolean get() = me?.settings?.p2pEnabled == true
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val meRepository: MeRepository,
    private val auth: AuthRepository,
    private val prefs: AppPreferences,
    buildInfo: BuildInfo,
) : ViewModel() {
    private val local = MutableStateFlow(SettingsUiState(versionName = buildInfo.versionName))

    val state: StateFlow<SettingsUiState> = combine(meRepository.me, local, prefs.dynamicColor) { me, l, dynamic -> l.copy(me = me, dynamicColor = dynamic) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    init {
        viewModelScope.launch { meRepository.restore() }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { meRepository.refresh() }
        loadDevices()
    }

    fun loadDevices() {
        local.update { if (it.devices is Load.Content) it else it.copy(devices = Load.Loading) }
        viewModelScope.launch {
            when (val r = meRepository.devices()) {
                is ApiResult.Success -> local.update { it.copy(devices = Load.Content(r.value)) }
                is ApiResult.Failure -> local.update { if (it.devices is Load.Content) it else it.copy(devices = Load.Failed(r.error)) }
            }
        }
    }

    fun setRegion(region: String) {
        if (region in SETTINGS_REGIONS && region != state.value.me?.settings?.region) patch(SettingsPatch(region = region))
    }

    /** Toggles one language; the contract needs at least one (`minItems: 1`), so the last one cannot be turned off. */
    fun toggleLanguage(language: String) {
        val current = state.value.me?.settings?.languages ?: return
        if (language !in SETTINGS_LANGUAGES) return
        val next = if (language in current) current - language else SETTINGS_LANGUAGES.filter { it in current || it == language }
        if (next.isEmpty()) return
        patch(SettingsPatch(languages = next))
    }

    fun setPreferredFormat(format: String) {
        if (format in SETTINGS_FORMATS && format != state.value.me?.settings?.preferFormat) patch(SettingsPatch(preferFormat = format))
    }

    fun setSourceMode(mode: String) {
        val s = state.value
        if (mode !in SourceMode.ALL || mode == s.me?.settings?.sourceMode) return
        if (mode == SourceMode.P2P && !s.p2pSourceModeAvailable) return
        patch(SettingsPatch(sourceMode = mode))
    }

    fun rename(displayName: String) {
        InputValidation.validateDisplayName(displayName)?.let { problem ->
            local.update { it.copy(nameProblem = problem) }
            return
        }
        local.update { it.copy(nameProblem = null, saving = true, error = null) }
        viewModelScope.launch {
            val r = meRepository.update(UpdateMeRequest(displayName = displayName.trim()))
            local.update { it.copy(saving = false, error = (r as? ApiResult.Failure)?.error) }
        }
    }

    fun clearNameProblem() = local.update { it.copy(nameProblem = null) }

    private fun patch(patch: SettingsPatch) {
        if (local.value.saving) return
        local.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val r = meRepository.updateSettings(patch)
            local.update { it.copy(saving = false, error = (r as? ApiResult.Failure)?.error) }
        }
    }

    fun createLinkCode() {
        if (local.value.creatingLinkCode) return
        local.update { it.copy(creatingLinkCode = true, error = null) }
        viewModelScope.launch {
            when (val r = meRepository.createLinkCode()) {
                is ApiResult.Success -> local.update { it.copy(creatingLinkCode = false, linkCode = r.value) }
                is ApiResult.Failure -> local.update { it.copy(creatingLinkCode = false, error = r.error) }
            }
        }
    }

    /** The one-time code is dropped as soon as its dialog closes. */
    fun dismissLinkCode() = local.update { it.copy(linkCode = null) }

    fun revokeDevice(id: String) {
        if (local.value.busyDeviceId != null) return
        local.update { it.copy(busyDeviceId = id, error = null) }
        viewModelScope.launch {
            val r = meRepository.revokeDevice(id)
            if (r is ApiResult.Success) loadDevices()
            local.update { it.copy(busyDeviceId = null, error = (r as? ApiResult.Failure)?.error) }
        }
    }

    fun consumeError() = local.update { it.copy(error = null) }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { prefs.setDynamicColor(enabled) }
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }
}
