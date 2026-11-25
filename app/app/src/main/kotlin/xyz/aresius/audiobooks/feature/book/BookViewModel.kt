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
import xyz.aresius.audiobooks.core.data.MeRepository
import xyz.aresius.audiobooks.core.download.LocalFiles
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.BookDetail
import xyz.aresius.audiobooks.core.network.Chapters
import xyz.aresius.audiobooks.core.network.LibraryEntry
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.ReleaseView
import xyz.aresius.audiobooks.core.player.PlayerControls
import xyz.aresius.audiobooks.core.player.PlayerPhase
import xyz.aresius.audiobooks.core.player.PlayerState
import xyz.aresius.audiobooks.core.player.ReleaseChoice
import xyz.aresius.audiobooks.core.player.ReleaseChooser
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.feature.common.routeAsin
import xyz.aresius.audiobooks.feature.common.toLoad

/** Whether Play can start this book now. */
sealed interface PlayAvailability {
    /** Streams with [provider], or plays what is on the phone ([provider] null). */
    data class Playable(val provider: Provider?) : PlayAvailability

    /** No debrid key and nothing downloaded: add a key in Settings. */
    data object NeedsKey : PlayAvailability

    data object NoReleases : PlayAvailability
}

data class BookUiState(
    val detail: Load<BookDetail> = Load.Loading,
    val refreshing: Boolean = false,
    /** The caller's library entry for this book (null = not saved). */
    val libraryEntry: LibraryEntry? = null,
    val libraryBusy: Boolean = false,
    val error: ApiError? = null,
    /** Tapped Download: that needs the download engine of a later phase. */
    val comingSoon: Boolean = false,
    /** Chapter marks of the catalog (shown as a list; the player falls back to the files when they do not fit). */
    val chapters: Load<Chapters> = Load.Loading,
    /** The release Play uses: the user's tap, else the remembered one, else the best one they can stream. */
    val chosen: ReleaseChoice? = null,
    val availability: PlayAvailability = PlayAvailability.NoReleases,
    val hasDownloads: Boolean = false,
    /** What the player does with THIS book right now (null when it plays another one or nothing). */
    val playerPhase: PlayerPhase? = null,
)

@HiltViewModel
class BookViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    private val player: PlayerControls,
    localFiles: LocalFiles,
    private val me: MeRepository,
) : ViewModel() {
    private val asin = savedStateHandle.routeAsin()
    private val local = MutableStateFlow(BookUiState())
    private val picked = MutableStateFlow<String?>(null)
    private val remembered = MutableStateFlow<String?>(null)
    private var askedAgain = false

    val state: StateFlow<BookUiState> = combine(local, library.entry(asin), localFiles.hasDownloads(asin), picked, remembered) { l, entry, downloads, pick, last ->
        val releases = (l.detail as? Load.Content)?.value?.releases.orEmpty()
        val chosen = ReleaseChooser.choose(releases, pick ?: last ?: entry?.releaseHash, Provider.fromWire(me.me.value?.settings?.preferredProvider))
        l.copy(
            libraryEntry = entry,
            hasDownloads = downloads,
            chosen = chosen,
            availability = availabilityOf(chosen, downloads),
        )
    }.combine(player.state) { s, p -> s.copy(playerPhase = p.phaseFor(asin)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    init {
        load(refresh = false)
        viewModelScope.launch {
            remembered.value = player.rememberedRelease(asin)
            me.restore()
            val r = catalog.chapters(asin)
            local.update { it.copy(chapters = r.toLoad(it.chapters)) }
        }
    }

    /** First load, Retry, and a refresh of the releases (the server may have found more since). */
    fun load(refresh: Boolean = true) {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true, detail = if (it.detail is Load.Content) it.detail else Load.Loading) }
        viewModelScope.launch {
            val r = catalog.book(asin, refresh)
            local.update { it.copy(refreshing = false, detail = r.toLoad(it.detail)) }
            // A cached page can predate the user's debrid key (the answer is per user): ask again once before saying "add a key".
            if (!refresh && !askedAgain && (r as? ApiResult.Success)?.value?.releases?.let { rs -> rs.isNotEmpty() && rs.none { it.usable.debrid.isNotEmpty() } } == true) {
                askedAgain = true
                load(refresh = true)
            }
        }
    }

    /** Listen with this release (remembered once the book plays). */
    fun choose(release: ReleaseView) {
        picked.value = release.infoHash
    }

    /** Starts the book with the chosen release; the caller opens the player. */
    fun play() {
        val s = state.value
        val book = (s.detail as? Load.Content)?.value?.book ?: return
        if (s.availability !is PlayAvailability.Playable) return
        player.play(asin, s.chosen?.release?.infoHash, book)
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

    fun onDownload() = local.update { it.copy(comingSoon = true) }

    fun consumeComingSoon() = local.update { it.copy(comingSoon = false) }

    fun consumeError() = local.update { it.copy(error = null) }
}

internal fun availabilityOf(chosen: ReleaseChoice?, hasDownloads: Boolean): PlayAvailability = when {
    chosen == null -> PlayAvailability.NoReleases
    chosen.provider != null || hasDownloads -> PlayAvailability.Playable(chosen.provider)
    else -> PlayAvailability.NeedsKey
}

/** The player's phase when it is busy with this book and not simply playing it: loading, preparing or failed. */
internal fun PlayerState.phaseFor(asin: String): PlayerPhase? =
    if (this.asin == asin && phase != PlayerPhase.Idle && phase != PlayerPhase.Ready) phase else null
