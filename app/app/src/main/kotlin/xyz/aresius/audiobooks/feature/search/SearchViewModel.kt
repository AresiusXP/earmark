package xyz.aresius.audiobooks.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import xyz.aresius.audiobooks.core.data.CatalogRepository
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.feature.common.toLoad

/** What the results area shows: nothing typed yet, too short, or a search in its three states. */
sealed interface SearchResultsState {
    data object Idle : SearchResultsState
    data object TooShort : SearchResultsState
    data class Results(val load: Load<List<Book>>) : SearchResultsState
}

data class SearchUiState(
    val query: String = "",
    val results: SearchResultsState = SearchResultsState.Idle,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val retry = MutableStateFlow(0)

    private val results: StateFlow<SearchResultsState> = combine(
        query.map { it.trim() }.debounce { if (it.length < MIN_LENGTH) 0L else DEBOUNCE_MS }.distinctUntilChanged(),
        retry,
    ) { q, attempt -> q to attempt }
        .flatMapLatest { (q, attempt) ->
            flow {
                when {
                    q.isEmpty() -> emit(SearchResultsState.Idle)
                    q.length < MIN_LENGTH -> emit(SearchResultsState.TooShort)
                    else -> {
                        emit(SearchResultsState.Results(Load.Loading))
                        emit(SearchResultsState.Results(catalog.search(q.take(MAX_LENGTH), refresh = attempt > 0).toLoad()))
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchResultsState.Idle)

    val state: StateFlow<SearchUiState> = combine(query, results) { q, r -> SearchUiState(q, r) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

    fun onQueryChange(text: String) {
        query.value = text
    }

    fun retry() {
        retry.value++
    }

    companion object {
        /** `q` has `minLength: 2` and `maxLength: 120` in the contract. */
        const val MIN_LENGTH = 2
        const val MAX_LENGTH = 120
        const val DEBOUNCE_MS = 400L
    }
}
