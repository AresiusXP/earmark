package xyz.aresius.audiobooks.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.data.CatalogRepository
import xyz.aresius.audiobooks.core.data.CatalogRepositoryImpl
import xyz.aresius.audiobooks.core.data.LibraryRepository
import xyz.aresius.audiobooks.core.data.MeRepository
import xyz.aresius.audiobooks.core.data.NetworkEvents
import xyz.aresius.audiobooks.core.data.NoNetworkEvents
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.LibraryEntry
import xyz.aresius.audiobooks.core.network.LibraryStatus
import xyz.aresius.audiobooks.core.player.PlayerControls
import xyz.aresius.audiobooks.core.player.PlayerPhase
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
    /** The player holds the hero's book (playing, paused or restored after a restart): its button toggles playback and its body opens the player. */
    val continueInPlayer: Boolean = false,
    val continuePlaying: Boolean = false,
    /** The hero's book is being opened, prepared by the provider or buffering. */
    val continueBusy: Boolean = false,
    /** The live position of the hero's book while the player holds it (the library entry only changes every 30 s). */
    val continuePositionMs: Long? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class DiscoverViewModel(
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    me: MeRepository,
    private val player: PlayerControls,
    networkEvents: NetworkEvents = NoNetworkEvents,
    private val debounceMs: Long = REFRESH_DEBOUNCE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val hourNow: () -> Int,
) : ViewModel() {
    @Inject
    constructor(catalog: CatalogRepository, library: LibraryRepository, me: MeRepository, player: PlayerControls, networkEvents: NetworkEvents) :
        this(catalog, library, me, player, networkEvents, REFRESH_DEBOUNCE_MS, System::currentTimeMillis, { LocalTime.now().hour })

    private val local = MutableStateFlow(DiscoverUiState(greeting = greetingForHour(hourNow(), eveningFromHour())))

    val state: StateFlow<DiscoverUiState> = combine(local, me.me, library.entries, player.state) { l, user, entries, playing ->
        val hero = entries.firstOrNull { it.status == LibraryStatus.LISTENING && it.positionMs > 0 }
        val mine = hero != null && playing.asin == hero.book.asin && playing.hasBook
        l.copy(
            name = user?.displayName,
            continueListening = hero,
            continueInPlayer = mine,
            continuePlaying = mine && playing.playing,
            continuePositionMs = if (mine && playing.phase == PlayerPhase.Ready) playing.positionMs else null,
            continueBusy = mine && (playing.buffering || playing.phase == PlayerPhase.Loading || playing.phase is PlayerPhase.Preparing),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    private var nextPage = 0
    private var loadJob: Job? = null
    private var loadedAtMs = 0L

    /** The screen came back (foreground, tab reopened) or the network returned: several of these in a row make one refresh. */
    private val triggers = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        start(refresh = false)
        // The hero comes from the library copy; a failed refresh just leaves what is cached.
        viewModelScope.launch { library.refresh() }
        viewModelScope.launch { merge(triggers, networkEvents.available).debounce(debounceMs).collect { refreshIfNeeded() } }
    }

    /** First load, retry after an error, and pull-to-refresh (which asks the server again instead of the 5-minute cache). */
    fun load(refresh: Boolean = true) {
        if (local.value.refreshing) return
        start(refresh)
    }

    /** The hero's round button while the player holds its book: pause when it plays, play (also after a restart) when it does not. */
    fun togglePlay() = player.togglePlay()

    /** Discover is on screen again: the app returned to the foreground or the tab was reopened. */
    fun onResumed() {
        triggers.tryEmit(Unit)
    }

    /**
     * A saved copy (or an error) is replaced as soon as the server answers again, also when an earlier request is still hanging; a fresh page is
     * only refreshed once its 5 minutes are over, so coming back to Discover never reshuffles what you were just looking at.
     */
    internal fun refreshIfNeeded() {
        val books = local.value.books
        val saved = books is Load.Failed || (books is Load.Content && books.stale)
        val old = books is Load.Content && clock() - loadedAtMs >= CatalogRepositoryImpl.FRESH_MS
        if (!saved && !old) return
        start(refresh = true)
        viewModelScope.launch { library.refresh() }
    }

    private fun start(refresh: Boolean) {
        loadJob?.cancel()
        local.update { it.copy(refreshing = true, greeting = greetingForHour(hourNow(), eveningFromHour()), books = if (it.books is Load.Content) it.books else Load.Loading) }
        loadJob = viewModelScope.launch {
            val r = catalog.discover(0, refresh)
            nextPage = 1
            if (r is ApiResult.Success && !r.stale) loadedAtMs = clock()
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

    companion object {
        const val REFRESH_DEBOUNCE_MS = 1_500L
    }
}
