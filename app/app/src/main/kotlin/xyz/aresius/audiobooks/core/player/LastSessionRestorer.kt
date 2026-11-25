package xyz.aresius.audiobooks.core.player

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.data.LibraryDao
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.LibraryStatus

/**
 * After a process restart the mini player is back before anyone presses play: the book the user listened to last (newest listening entry of the
 * cached library; the player's own progress row when that is newer) is published to [PlayerHub] as a [PlayerState.restored] state. Nothing
 * is played, no service is started and no media session exists: pressing play, or opening the full player, loads the book from where it stood.
 */
@Singleton
class LastSessionRestorer @Inject constructor(
    private val sessions: SessionStore,
    private val library: LibraryDao,
    private val dao: PlayerDao,
    private val hub: PlayerHub,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            sessions.session.map { it?.userId }.distinctUntilChanged().collect { user -> if (user != null) restore(user) }
        }
    }

    suspend fun restore(userId: String) {
        if (hub.state.value.hasBook) return
        val entry = library.observeAll(userId).first().firstOrNull { it.status == LibraryStatus.LISTENING && it.positionMs > 0 } ?: return
        val book = runCatching { AudiobooksJson.decodeFromString(Book.serializer(), entry.bookJson) }.getOrNull() ?: return
        val progress = dao.progress(userId, entry.asin)?.takeIf { it.updatedAt >= entry.updatedAt }
        if (progress != null && progress.status != LibraryStatus.LISTENING) return
        val position = progress?.positionMs ?: entry.positionMs
        val restored = PlayerState(
            phase = PlayerPhase.Ready,
            asin = entry.asin,
            book = book,
            releaseHash = progress?.releaseHash ?: entry.releaseHash,
            positionMs = position,
            durationMs = book.runtimeMin * 60_000L,
            durationExact = false,
            speed = (progress?.speed ?: entry.speed).toFloat(),
            restored = true,
        )
        hub.update { if (hasBook) this else restored }
    }
}
