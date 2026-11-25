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
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Book

/**
 * What the UI uses to listen: [state] (published by the service) and commands (sent to the media session through a MediaController, which
 * also starts the service). All functions return at once; the work happens in the service.
 */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    hub: PlayerHub,
) {
    val state: StateFlow<PlayerState> = hub.state
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

    /** Opens and plays [asin]; [releaseHash] = the release the user picked (remembered for next time), [book] = what is known of it for the first frame. */
    fun play(asin: String, releaseHash: String? = null, book: Book? = null, autoplay: Boolean = true) = command(PlayerCommands.LOAD) {
        putString(PlayerCommands.KEY_ASIN, asin)
        releaseHash?.let { putString(PlayerCommands.KEY_RELEASE, it) }
        book?.let { putString(PlayerCommands.KEY_BOOK_JSON, AudiobooksJson.encodeToString(Book.serializer(), it)) }
        putBoolean(PlayerCommands.KEY_AUTOPLAY, autoplay)
    }

    fun togglePlay() = withController { if (it.isPlaying || it.playWhenReady) it.pause() else it.play() }

    fun pause() = withController { it.pause() }

    fun skipBack() = command(PlayerCommands.SKIP_BACK)

    fun skipForward() = command(PlayerCommands.SKIP_FORWARD)

    fun nextChapter() = withController { it.seekToNext() }

    fun previousChapter() = withController { it.seekToPrevious() }

    /** [positionMs] is a position in the whole book. */
    fun seekTo(positionMs: Long) = command(PlayerCommands.SEEK_BOOK) { putLong(PlayerCommands.KEY_POSITION_MS, positionMs) }

    fun setSpeed(speed: Float) = command(PlayerCommands.SET_SPEED) { putFloat(PlayerCommands.KEY_SPEED, speed) }

    fun startSleepTimer(minutes: Int) = command(PlayerCommands.SLEEP_START) { putInt(PlayerCommands.KEY_MINUTES, minutes) }

    fun startSleepAtEndOfChapter() = startSleepTimer(PlayerCommands.END_OF_CHAPTER)

    fun cancelSleepTimer() = command(PlayerCommands.SLEEP_CANCEL)

    fun extendSleepTimer() = command(PlayerCommands.SLEEP_EXTEND)

    fun retry() = command(PlayerCommands.RETRY)

    /** Stops and dismisses the player (mini player and notification). */
    fun close() = command(PlayerCommands.CLOSE)
}
