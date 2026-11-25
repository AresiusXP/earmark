package xyz.aresius.audiobooks.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.data.CatalogRepository
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.feature.common.Load

data class DiscoverUiState(
    val books: Load<List<Book>> = Load.Loading,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    /** False once a page came back empty. */
    val canLoadMore: Boolean = true,
    val loadMoreError: ApiError? = null,
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()
    private var nextPage = 0

    init {
        load(refresh = false)
    }

    /** First load, retry after an error, and pull-to-refresh (which asks the server again instead of the 5-minute cache). */
    fun load(refresh: Boolean = true) {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true, books = if (it.books is Load.Content) it.books else Load.Loading) }
        viewModelScope.launch {
            val r = catalog.discover(0, refresh)
            nextPage = 1
            _state.update { s ->
                when (r) {
                    is ApiResult.Success -> s.copy(
                        books = Load.Content(r.value, r.stale),
                        refreshing = false,
                        canLoadMore = r.value.isNotEmpty() && !r.stale,
                        loadMoreError = null,
                    )
                    is ApiResult.Failure -> s.copy(refreshing = false, books = if (s.books is Load.Content) s.books.copy(stale = true) else Load.Failed(r.error))
                }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val current = (s.books as? Load.Content)?.value ?: return
        if (s.loadingMore || s.refreshing || !s.canLoadMore) return
        _state.update { it.copy(loadingMore = true, loadMoreError = null) }
        viewModelScope.launch {
            when (val r = catalog.discover(nextPage)) {
                is ApiResult.Success -> {
                    nextPage++
                    val known = current.mapTo(HashSet()) { it.asin }
                    val fresh = r.value.filter { it.asin !in known }
                    _state.update {
                        it.copy(loadingMore = false, books = Load.Content(current + fresh), canLoadMore = r.value.isNotEmpty())
                    }
                }
                is ApiResult.Failure -> _state.update { it.copy(loadingMore = false, loadMoreError = r.error) }
            }
        }
    }
}
