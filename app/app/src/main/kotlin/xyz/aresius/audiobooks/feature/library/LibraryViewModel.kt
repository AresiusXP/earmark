package xyz.aresius.audiobooks.feature.library

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
import xyz.aresius.audiobooks.core.data.LibraryRepository
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.LibraryEntry

data class LibraryUiState(
    /** All cached entries, most recently touched first. */
    val entries: List<LibraryEntry> = emptyList(),
    /** One of [xyz.aresius.audiobooks.core.network.LibraryStatus] or null for all. */
    val filter: String? = null,
    /** False until the first refresh finished (success or failure): an empty list before that is "loading", not "empty". */
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: ApiError? = null,
) {
    val visible: List<LibraryEntry> get() = if (filter == null) entries else entries.filter { it.status == filter }
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
) : ViewModel() {
    private val local = MutableStateFlow(LibraryUiState())

    val state: StateFlow<LibraryUiState> = combine(library.entries, local) { entries, l -> l.copy(entries = entries) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    init {
        refresh()
    }

    fun refresh() {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val r = library.refresh()
            local.update { it.copy(refreshing = false, loaded = true, error = (r as? ApiResult.Failure)?.error) }
        }
    }

    fun setFilter(status: String?) = local.update { it.copy(filter = status) }
}
