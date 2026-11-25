package xyz.aresius.audiobooks.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.data.CatalogRepository
import xyz.aresius.audiobooks.core.data.LibraryRepository
import xyz.aresius.audiobooks.core.data.MeRepository
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.LibraryEntry
import xyz.aresius.audiobooks.core.network.LibraryStatus
import xyz.aresius.audiobooks.feature.common.Load

data class DiscoverUiState(
    val books: Load<List<Book>> = Load.Loading,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    /** False once a page came back empty. */
    val canLoadMore: Boolean = true,
    val loadMoreError: ApiError? = null,
    val greeting: Greeting = Greeting.Evening,
    /** The user's display name for the greeting; null until the profile is known. */
    val name: String? = null,
    /** The listening entry with progress that was touched last: the "Continue listening" hero. */
    val continueListening: LibraryEntry? = null,
)

@HiltViewModel
class DiscoverViewModel(
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    me: MeRepository,
    private val hourNow: () -> Int,
) : ViewModel() {
    @Inject
    constructor(catalog: CatalogRepository, library: LibraryRepository, me: MeRepository) :
        this(catalog, library, me, { LocalTime.now().hour })

    private val local = MutableStateFlow(DiscoverUiState(greeting = greetingForHour(hourNow())))

    val state: StateFlow<DiscoverUiState> = combine(local, me.me, library.entries) { l, user, entries ->
        l.copy(
            name = user?.displayName,
            continueListening = entries.firstOrNull { it.status == LibraryStatus.LISTENING && it.positionMs > 0 },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    private var nextPage = 0

    init {
        load(refresh = false)
        // The hero comes from the library copy; a failed refresh just leaves what is cached.
        viewModelScope.launch { library.refresh() }
    }

    /** First load, retry after an error, and pull-to-refresh (which asks the server again instead of the 5-minute cache). */
    fun load(refresh: Boolean = true) {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true, greeting = greetingForHour(hourNow()), books = if (it.books is Load.Content) it.books else Load.Loading) }
        viewModelScope.launch {
            val r = catalog.discover(0, refresh)
            nextPage = 1
            local.update { s ->
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
        val s = local.value
        val current = (s.books as? Load.Content)?.value ?: return
        if (s.loadingMore || s.refreshing || !s.canLoadMore) return
        local.update { it.copy(loadingMore = true, loadMoreError = null) }
        viewModelScope.launch {
            when (val r = catalog.discover(nextPage)) {
                is ApiResult.Success -> {
                    nextPage++
                    val known = current.mapTo(HashSet()) { it.asin }
                    val fresh = r.value.filter { it.asin !in known }
                    local.update { it.copy(loadingMore = false, books = Load.Content(current + fresh), canLoadMore = r.value.isNotEmpty()) }
                }
                is ApiResult.Failure -> local.update { it.copy(loadingMore = false, loadMoreError = r.error) }
            }
        }
    }
}
