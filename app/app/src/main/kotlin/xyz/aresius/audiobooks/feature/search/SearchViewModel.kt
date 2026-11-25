package xyz.aresius.audiobooks.feature.search

import androidx.lifecycle.SavedStateHandle
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
    private val saved: SavedStateHandle,
) : ViewModel() {
    // The query is kept in the saved state: it survives leaving the tab and process death, and the results are searched again from it
    // (the catalog's own copy answers within its 5 minutes, so a return to the tab shows the same list at once).
    private val restored = saved.get<String>(KEY_QUERY).orEmpty().take(MAX_QUERY_KEPT)
    private val query = MutableStateFlow(restored)
    private val retry = MutableStateFlow(0)

    private val results: StateFlow<SearchResultsState> = combine(
        query.map { it.trim() }.debounce { if (it.length < MIN_LENGTH || it == restored.trim()) 0L else DEBOUNCE_MS }.distinctUntilChanged(),
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
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialResults(restored.trim()))

    val state: StateFlow<SearchUiState> = combine(query, results) { q, r -> SearchUiState(q, r) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState(restored, initialResults(restored.trim())))

    fun onQueryChange(text: String) {
        query.value = text
        saved[KEY_QUERY] = text.take(MAX_QUERY_KEPT)
    }

    private fun initialResults(q: String): SearchResultsState = when {
        q.isEmpty() -> SearchResultsState.Idle
        q.length < MIN_LENGTH -> SearchResultsState.TooShort
        else -> SearchResultsState.Results(Load.Loading)
    }

    fun retry() {
        retry.value++
    }

    companion object {
        /** `q` has `minLength: 2` and `maxLength: 120` in the contract. */
        const val KEY_QUERY = "query"
        private const val MAX_QUERY_KEPT = 200
        const val MIN_LENGTH = 2
        const val MAX_LENGTH = 120
        const val DEBOUNCE_MS = 400L
    }
}
