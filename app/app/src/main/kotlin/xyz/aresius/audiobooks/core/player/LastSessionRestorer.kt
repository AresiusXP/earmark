package xyz.aresius.audiobooks.core.player

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
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
            sessions.session.map { it?.userId }.distinctUntilChanged().collectLatest { user -> if (user != null) follow(user) }
        }
    }

    /** Restores once from the cached library, then follows it while nothing was played: a refresh that brings newer progress (another phone) updates the mini player. */
    private suspend fun follow(userId: String) {
        userTookOver = false
        restore(userId)
        // Later changes only refresh a restored state (or fill an empty one until the user played something): closing the player must not
        // bring the mini player back.
        library.observeAll(userId).collect { if (hub.state.value.restored || !userTookOver) restore(userId) }
    }

    private var userTookOver = false

    suspend fun restore(userId: String) {
        val current = hub.state.value
        if (current.hasBook && !current.restored) {
            userTookOver = true
            return
        }
        val entry = library.observeAll(userId).first().firstOrNull { it.status == LibraryStatus.LISTENING && it.positionMs > 0 }
        val book = entry?.let { runCatching { AudiobooksJson.decodeFromString(Book.serializer(), it.bookJson) }.getOrNull() }
        val progress = entry?.let { dao.progress(userId, it.asin)?.takeIf { p -> p.updatedAt >= it.updatedAt } }
        if (entry == null || book == null || (progress != null && progress.status != LibraryStatus.LISTENING)) {
            hub.update { if (restored) PlayerState() else this }
            return
        }
        val position = progress?.positionMs ?: entry.positionMs
        val next = PlayerState(
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
        hub.update { if (hasBook && !restored) this else next }
    }
}
