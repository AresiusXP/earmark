package xyz.aresius.audiobooks.core.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Book

/** What the screens may ask of the player (an interface so view models are tested without a service). */
interface PlayerControls {
    val state: StateFlow<PlayerState>

    /** Opens and plays [asin]; [releaseHash] = the release the user picked (remembered for next time), [book] = what is known of it for the first frame. */
    fun play(asin: String, releaseHash: String? = null, book: Book? = null, autoplay: Boolean = true)

    /** The release the user last played for this book, if any. */
    suspend fun rememberedRelease(asin: String): String?
    fun togglePlay()

    /** Loads the restored last-session book (paused) so the full player has everything it shows; does nothing when the player is not in the restored state. */
    fun prepareRestored() {}
    fun pause()
    fun skipBack()
    fun skipForward()
    fun nextChapter()
    fun previousChapter()

    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)

    /** [minutes] > 0, or [PlayerCommands.END_OF_CHAPTER]. */
    fun startSleepTimer(minutes: Int)
    fun cancelSleepTimer()
    fun extendSleepTimer()
    fun retry()

    fun close()
}

/**
 * What the UI uses to listen: [state] (published by the service) and commands (sent to the media session through a MediaController, which
 * also starts the service). All functions return at once; the work happens in the service.
 */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hub: PlayerHub,
    private val dao: PlayerDao,
    private val sessions: SessionStore,
) : PlayerControls {
    override val state: StateFlow<PlayerState> = hub.state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var future: ListenableFuture<MediaController>? = null

    private fun controller(): ListenableFuture<MediaController> {
        future?.let { f ->
            if (!f.isDone || runCatching { f.get().isConnected }.getOrDefault(false)) return f
        }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        return MediaController.Builder(context, token).buildAsync().also { future = it }
    }

    private fun withController(block: (MediaController) -> Unit) {
        scope.launch {
            val c = suspendCancellableCoroutine { cont ->
                val f = controller()
                f.addListener({ cont.resume(runCatching { f.get() }.getOrNull()) }, MoreExecutors.directExecutor())
            }
            if (c != null) block(c)
        }
    }

    private fun command(action: String, args: Bundle.() -> Unit = {}) = withController { it.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle().apply(args)) }

    override fun play(asin: String, releaseHash: String?, book: Book?, autoplay: Boolean) {
        // The screen that opens next must not see "nothing is playing" before the service has picked the command up.
        hub.update { if (this.asin == asin && phase != PlayerPhase.Idle && !restored) this else PlayerState(phase = PlayerPhase.Loading, asin = asin, book = book ?: this.book.takeIf { this.asin == asin }) }
        send(asin, releaseHash, book, autoplay)
    }

    private fun send(asin: String, releaseHash: String?, book: Book?, autoplay: Boolean) = command(PlayerCommands.LOAD) {
        putString(PlayerCommands.KEY_ASIN, asin)
        releaseHash?.let { putString(PlayerCommands.KEY_RELEASE, it) }
        book?.let { putString(PlayerCommands.KEY_BOOK_JSON, AudiobooksJson.encodeToString(Book.serializer(), it)) }
        putBoolean(PlayerCommands.KEY_AUTOPLAY, autoplay)
    }

    override fun togglePlay() {
        val s = hub.state.value
        // The restored mini player has no media loaded yet: playing it loads the book from the stored position.
        if (s.restored && s.asin != null) return play(s.asin, s.releaseHash, s.book)
        withController { if (it.isPlaying || it.playWhenReady) it.pause() else it.play() }
    }

    override fun prepareRestored() {
        val s = hub.state.value
        if (s.restored && s.asin != null) play(s.asin, s.releaseHash, s.book, autoplay = false)
    }

    override fun pause() = withController { it.pause() }

    override fun skipBack() = command(PlayerCommands.SKIP_BACK)

    override fun skipForward() = command(PlayerCommands.SKIP_FORWARD)

    override fun nextChapter() = withController { it.seekToNext() }

    override fun previousChapter() = withController { it.seekToPrevious() }

    override fun seekTo(positionMs: Long) = command(PlayerCommands.SEEK_BOOK) { putLong(PlayerCommands.KEY_POSITION_MS, positionMs) }

    override fun setSpeed(speed: Float) = command(PlayerCommands.SET_SPEED) { putFloat(PlayerCommands.KEY_SPEED, speed) }

    override fun startSleepTimer(minutes: Int) = command(PlayerCommands.SLEEP_START) { putInt(PlayerCommands.KEY_MINUTES, minutes) }

    override fun cancelSleepTimer() = command(PlayerCommands.SLEEP_CANCEL)

    override fun extendSleepTimer() = command(PlayerCommands.SLEEP_EXTEND)

    override fun retry() = command(PlayerCommands.RETRY)

    override suspend fun rememberedRelease(asin: String): String? = sessions.load()?.userId?.let { dao.pref(it, asin)?.releaseHash }

    override fun close() = command(PlayerCommands.CLOSE)
}
