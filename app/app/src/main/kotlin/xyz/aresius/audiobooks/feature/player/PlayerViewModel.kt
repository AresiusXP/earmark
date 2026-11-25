package xyz.aresius.audiobooks.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.player.BookmarkRepository
import xyz.aresius.audiobooks.core.player.PlayerBookmark
import xyz.aresius.audiobooks.core.player.PlayerControls
import xyz.aresius.audiobooks.core.player.PlayerState
import xyz.aresius.audiobooks.core.player.PlaybackRules

/** The player screen, the mini player and the "play" buttons of other screens all go through here. */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controls: PlayerControls,
    private val bookmarkRepository: BookmarkRepository,
) : ViewModel() {
    val state: StateFlow<PlayerState> = controls.state

    @OptIn(ExperimentalCoroutinesApi::class)
    val bookmarks: StateFlow<List<PlayerBookmark>> = controls.state.map { it.asin }.distinctUntilChanged()
        .flatMapLatest { asin -> if (asin == null) flowOf(emptyList()) else bookmarkRepository.observe(asin) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Whenever another book opens: send what is owed to the server and merge its bookmarks.
        viewModelScope.launch {
            controls.state.map { it.asin }.distinctUntilChanged().collect { asin -> if (asin != null) bookmarkRepository.sync(asin) }
        }
    }

    fun play(asin: String, releaseHash: String? = null, book: Book? = null) = controls.play(asin, releaseHash, book)
    fun togglePlay() = controls.togglePlay()
    fun skipBack() = controls.skipBack()
    fun skipForward() = controls.skipForward()
    fun nextChapter() = controls.nextChapter()
    fun previousChapter() = controls.previousChapter()
    fun seekTo(positionMs: Long) = controls.seekTo(positionMs)
    fun retry() = controls.retry()
    fun close() = controls.close()
    fun setSpeed(speed: Float) = controls.setSpeed(PlaybackRules.clampSpeed(speed))
    fun startSleep(minutes: Int) = controls.startSleepTimer(minutes)
    fun cancelSleep() = controls.cancelSleepTimer()
    fun extendSleep() = controls.extendSleepTimer()

    /** A bookmark at the current position of the book. */
    fun addBookmark(note: String?) {
        val s = controls.state.value
        val asin = s.asin ?: return
        viewModelScope.launch { bookmarkRepository.add(asin, s.positionMs, note) }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { bookmarkRepository.delete(id) }
    }
}
