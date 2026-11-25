package xyz.aresius.audiobooks.core.player

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.Provider

/** Why the player could not play; the UI turns each into words and, where it can, an action. */
enum class FailureKind {
    NotSignedIn,

    /** No catalog entry and none cached, or the server could not be reached for it. */
    Network,

    /** The book has no release at all. */
    NoReleases,

    /** Files that are not on the phone, and neither a debrid key nor a download can supply them: "add a debrid key". */
    NeedsSource,

    /** The provider answered 403 `no_debrid_key` / `bad_debrid_key`. */
    NoDebridKey,
    BadDebridKey,

    /** The provider failed the release (the message says why: no seeders, busy...). */
    Provider,
    RateLimited,

    /** The stream broke (connection) while playing; retry resumes at the same position. */
    Stream,
    Unknown,
}

sealed interface PlayerPhase {
    data object Idle : PlayerPhase
    data object Loading : PlayerPhase

    /** The debrid provider is still fetching the release: [progress] 0..100 when it says. */
    data class Preparing(val progress: Int?, val reason: String?) : PlayerPhase
    data class Failed(val kind: FailureKind, val message: String? = null) : PlayerPhase
    data object Ready : PlayerPhase
}

/** Everything the UI shows about the player; written by the playback service, read by the UI (same process). */
data class PlayerState(
    val phase: PlayerPhase = PlayerPhase.Idle,
    val asin: String? = null,
    val book: Book? = null,
    val releaseHash: String? = null,
    val provider: Provider? = null,
    val playing: Boolean = false,
    /** Wants to play but waits for data. */
    val buffering: Boolean = false,
    val ended: Boolean = false,
    /** Position and length of the WHOLE book. */
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** False while some file durations are still estimates. */
    val durationExact: Boolean = true,
    val chapters: List<BookChapter> = emptyList(),
    val chapterSource: ChapterSource = ChapterSource.Files,
    val chapterIndex: Int = -1,
    val speed: Float = 1f,
    val sleep: SleepState = SleepState.Off,
    val skipBackMs: Long = PlaybackRules.DEFAULT_SKIP_S * 1000L,
    val skipForwardMs: Long = PlaybackRules.DEFAULT_SKIP_S * 1000L,
    val fileCount: Int = 0,
    /** Bumped when the book has finished loading: lets the UI tell a new load from an update. */
    val loadId: Long = 0,
) {
    val hasBook: Boolean get() = phase != PlayerPhase.Idle
    val chapter: BookChapter? get() = chapters.getOrNull(chapterIndex)
    val chapterPositionMs: Long get() = chapter?.let { (positionMs - it.startMs).coerceIn(0, it.lengthMs) } ?: positionMs
    val chapterDurationMs: Long get() = chapter?.lengthMs ?: durationMs
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
}

/** The one place the service publishes [PlayerState]; the UI collects it. Process-wide (the service runs in the app's process). */
@Singleton
class PlayerHub @Inject constructor() {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    fun update(block: PlayerState.() -> PlayerState) = _state.update(block)

    fun reset() {
        _state.value = PlayerState()
    }
}
