package xyz.aresius.audiobooks.feature.book

import androidx.lifecycle.SavedStateHandle
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
import xyz.aresius.audiobooks.core.data.CatalogRepository
import xyz.aresius.audiobooks.core.data.LibraryRepository
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.BookDetail
import xyz.aresius.audiobooks.core.network.LibraryEntry
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.feature.common.routeAsin
import xyz.aresius.audiobooks.feature.common.toLoad

data class BookUiState(
    val detail: Load<BookDetail> = Load.Loading,
    val refreshing: Boolean = false,
    /** The caller's library entry for this book (null = not saved). */
    val libraryEntry: LibraryEntry? = null,
    val libraryBusy: Boolean = false,
    val error: ApiError? = null,
    /** Tapped Download or Play: those need the player and download engine of a later phase. */
    val comingSoon: Boolean = false,
)

@HiltViewModel
class BookViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
) : ViewModel() {
    private val asin = savedStateHandle.routeAsin()
    private val local = MutableStateFlow(BookUiState())

    val state: StateFlow<BookUiState> = combine(local, library.entry(asin)) { l, entry -> l.copy(libraryEntry = entry) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    init {
        load(refresh = false)
    }

    /** First load, Retry, and a refresh of the releases (the server may have found more since). */
    fun load(refresh: Boolean = true) {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true, detail = if (it.detail is Load.Content) it.detail else Load.Loading) }
        viewModelScope.launch {
            val r = catalog.book(asin, refresh)
            local.update { it.copy(refreshing = false, detail = r.toLoad(it.detail)) }
        }
    }

    fun toggleLibrary() {
        val s = state.value
        val book = (s.detail as? Load.Content)?.value?.book ?: return
        if (s.libraryBusy) return
        local.update { it.copy(libraryBusy = true, error = null) }
        viewModelScope.launch {
            val r = if (s.libraryEntry == null) library.add(book) else library.remove(book.asin)
            local.update { it.copy(libraryBusy = false, error = (r as? ApiResult.Failure)?.error) }
        }
    }

    fun onDownloadOrPlay() = local.update { it.copy(comingSoon = true) }

    fun consumeComingSoon() = local.update { it.copy(comingSoon = false) }

    fun consumeError() = local.update { it.copy(error = null) }
}
