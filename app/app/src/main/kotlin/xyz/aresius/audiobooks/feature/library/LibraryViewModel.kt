package xyz.aresius.audiobooks.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.data.LibraryRepository
import xyz.aresius.audiobooks.core.download.DownloadedBook
import xyz.aresius.audiobooks.core.download.DownloadsRepository
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.LibraryEntry
import xyz.aresius.audiobooks.core.network.LibraryStatus

/** Filter value of the "Downloaded" chip (next to the library statuses). */
const val FILTER_DOWNLOADED = "downloaded"

data class LibraryUiState(
    /** All cached entries, most recently touched first. */
    val entries: List<LibraryEntry> = emptyList(),
    /** Books that are fully on this phone (they play offline). */
    val downloaded: List<Book> = emptyList(),
    /** One of [xyz.aresius.audiobooks.core.network.LibraryStatus], [FILTER_DOWNLOADED] or null for all. */
    val filter: String? = null,
    /** False until the first refresh finished (success or failure): an empty list before that is "loading", not "empty". */
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: ApiError? = null,
) {
    val downloadedAsins: Set<String> get() = downloaded.mapTo(HashSet()) { it.asin }

    /** Downloaded books the library does not list (removed from it, or the list could not be loaded offline) show as "saved". */
    private val offlineExtras: List<LibraryEntry>
        get() = downloaded.filter { d -> entries.none { it.book.asin == d.asin } }.map { LibraryEntry(it, LibraryStatus.SAVED) }

    val visible: List<LibraryEntry>
        get() = when (filter) {
            null -> if (entries.isEmpty()) offlineExtras else entries
            FILTER_DOWNLOADED -> (entries + offlineExtras).filter { it.book.asin in downloadedAsins }
            else -> entries.filter { it.status == filter }
        }
}

@HiltViewModel
class LibraryViewModel(
    private val library: LibraryRepository,
    downloadedBooks: Flow<List<DownloadedBook>> = flowOf(emptyList()),
) : ViewModel() {
    @Inject
    constructor(library: LibraryRepository, downloads: DownloadsRepository) : this(library, downloads.downloads)

    private val local = MutableStateFlow(LibraryUiState())

    val state: StateFlow<LibraryUiState> = combine(library.entries, downloadedBooks, local) { entries, downloaded, l ->
        l.copy(entries = entries, downloaded = downloaded.filter { it.state.isCompleted }.map { it.book })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

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
