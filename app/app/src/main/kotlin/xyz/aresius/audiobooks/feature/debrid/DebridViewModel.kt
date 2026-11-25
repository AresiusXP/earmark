package xyz.aresius.audiobooks.feature.debrid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.data.DebridRepository
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.DebridStatus
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.feature.common.Load

/** One provider's row: its stored status (never the key), the key being typed, and what the last action said. */
data class ProviderUi(
    val provider: Provider,
    val status: DebridStatus? = null,
    /** The user is entering a key (a new one, or replacing the stored one). */
    val editing: Boolean = false,
    val keyInput: String = "",
    val busy: Boolean = false,
    val error: ApiError? = null,
) {
    val configured: Boolean get() = status?.configured == true
    val canSave: Boolean get() = keyInput.trim().length >= MIN_KEY_LENGTH && !busy

    companion object {
        /** `api_key` has `minLength: 8` in the contract. */
        const val MIN_KEY_LENGTH = 8
    }
}

data class DebridUiState(
    val load: Load<Unit> = Load.Loading,
    val providers: List<ProviderUi> = Provider.entries.map { ProviderUi(it) },
) {
    /** At least one stored key the server found working. */
    val hasWorkingKey: Boolean get() = providers.any { it.status?.isOk == true }
}

/** Debrid keys of the signed-in user: used by the onboarding step and by Settings. A typed key lives only here, until it is sent. */
@HiltViewModel
class DebridViewModel @Inject constructor(
    private val repository: DebridRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DebridUiState())
    val state: StateFlow<DebridUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { if (it.load is Load.Content) it else it.copy(load = Load.Loading) }
        viewModelScope.launch {
            when (val r = repository.list()) {
                is ApiResult.Success -> _state.update { s ->
                    s.copy(
                        load = Load.Content(Unit),
                        providers = s.providers.map { p -> p.copy(status = r.value.firstOrNull { it.provider == p.provider.wire }) },
                    )
                }
                is ApiResult.Failure -> _state.update { if (it.load is Load.Content) it else it.copy(load = Load.Failed(r.error)) }
            }
        }
    }

    fun startEditing(provider: Provider) = updateProvider(provider) { it.copy(editing = true, keyInput = "", error = null) }

    fun cancelEditing(provider: Provider) = updateProvider(provider) { it.copy(editing = false, keyInput = "", error = null) }

    fun onKeyChange(provider: Provider, text: String) = updateProvider(provider) { it.copy(keyInput = text, error = null) }

    /** PUT validates the key against the provider and stores it; a rejected key (`400 bad_debrid_key`) stays in the field to fix. */
    fun save(provider: Provider) {
        val row = row(provider)
        if (!row.canSave) return
        updateProvider(provider) { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.put(provider, row.keyInput)) {
                is ApiResult.Success -> updateProvider(provider) { it.copy(busy = false, editing = false, keyInput = "", status = r.value) }
                is ApiResult.Failure -> updateProvider(provider) { it.copy(busy = false, error = r.error) }
            }
        }
    }

    fun recheck(provider: Provider) {
        if (row(provider).busy) return
        updateProvider(provider) { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.recheck(provider)) {
                is ApiResult.Success -> updateProvider(provider) { it.copy(busy = false, status = r.value) }
                is ApiResult.Failure -> updateProvider(provider) { it.copy(busy = false, error = r.error) }
            }
        }
    }

    fun remove(provider: Provider) {
        if (row(provider).busy) return
        updateProvider(provider) { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.delete(provider)) {
                is ApiResult.Success -> updateProvider(provider) {
                    it.copy(busy = false, editing = false, keyInput = "", status = DebridStatus(provider = provider.wire, configured = false))
                }
                is ApiResult.Failure -> updateProvider(provider) { it.copy(busy = false, error = r.error) }
            }
        }
    }

    private fun row(provider: Provider) = _state.value.providers.first { it.provider == provider }

    private fun updateProvider(provider: Provider, transform: (ProviderUi) -> ProviderUi) {
        _state.update { s -> s.copy(providers = s.providers.map { if (it.provider == provider) transform(it) else it }) }
    }
}
