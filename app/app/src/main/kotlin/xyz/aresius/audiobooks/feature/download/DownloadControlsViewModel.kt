package xyz.aresius.audiobooks.feature.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.data.MeRepository
import xyz.aresius.audiobooks.core.download.BookDownloadState
import xyz.aresius.audiobooks.core.download.DownloadChoices
import xyz.aresius.audiobooks.core.download.DownloadsRepository
import xyz.aresius.audiobooks.core.download.EnqueueResult
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.ReleaseView

/** What the download controls of one or more books need; shared by Book detail and (later) the player through [DownloadButton] / [DownloadStatus]. */
@HiltViewModel
class DownloadControlsViewModel @Inject constructor(
    private val downloads: DownloadsRepository,
    private val me: MeRepository,
) : ViewModel() {
    private val states = HashMap<String, StateFlow<BookDownloadState>>()
    private val hashes = HashMap<String, StateFlow<String?>>()

    /** The last start attempt that did not start (needs a key, no space, ...), per asin; shown under the button until the next try. */
    private val _rejected = MutableStateFlow<Map<String, EnqueueResult>>(emptyMap())
    val rejected: StateFlow<Map<String, EnqueueResult>> = _rejected

    fun state(asin: String): StateFlow<BookDownloadState> = states.getOrPut(asin) {
        downloads.state(asin).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDownloadState.None)
    }

    /** The release hash of this book's download, if there is one. */
    fun releaseHash(asin: String): StateFlow<String?> = hashes.getOrPut(asin) {
        downloads.releaseHash(asin).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    /** Whether [release] can be downloaded at all with the user's keys / BitTorrent switch. */
    fun canDownload(release: ReleaseView): Boolean = DownloadChoices.choose(release, me.me.value?.settings?.preferredProvider) != null

    /** The best release to offer on the book's main button: the first (the server ranks them) that the user can get. */
    fun bestRelease(releases: List<ReleaseView>): ReleaseView? = releases.firstOrNull { canDownload(it) } ?: releases.firstOrNull()

    fun start(book: Book, release: ReleaseView) {
        viewModelScope.launch {
            val choice = DownloadChoices.choose(release, me.me.value?.settings?.preferredProvider)
            val result = downloads.enqueue(book, release, choice)
            _rejected.update { if (result == EnqueueResult.Started) it - book.asin else it + (book.asin to result) }
        }
    }

    fun retry(asin: String) {
        viewModelScope.launch {
            _rejected.update { it - asin }
            downloads.retry(asin)
        }
    }

    fun delete(asin: String) {
        viewModelScope.launch {
            _rejected.update { it - asin }
            downloads.delete(asin)
        }
    }
}
