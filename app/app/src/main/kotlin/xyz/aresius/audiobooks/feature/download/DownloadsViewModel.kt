package xyz.aresius.audiobooks.feature.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.download.DownloadedBook
import xyz.aresius.audiobooks.core.download.DownloadsRepository
import xyz.aresius.audiobooks.core.download.StorageInfo

data class DownloadsUiState(
    val books: List<DownloadedBook> = emptyList(),
    val storage: StorageInfo? = null,
    val loaded: Boolean = false,
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(private val downloads: DownloadsRepository) : ViewModel() {
    val state: StateFlow<DownloadsUiState> = combine(downloads.downloads, downloads.storageInfo) { books, storage ->
        DownloadsUiState(books, storage, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun delete(asin: String) {
        viewModelScope.launch { downloads.delete(asin) }
    }

    fun pause(asin: String) {
        viewModelScope.launch { downloads.pause(asin) }
    }

    fun resume(asin: String) {
        viewModelScope.launch { downloads.resume(asin) }
    }

    fun retry(asin: String) {
        viewModelScope.launch { downloads.retry(asin) }
    }

    fun deleteAll() {
        viewModelScope.launch { downloads.deleteAll() }
    }
}
