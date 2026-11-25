package xyz.aresius.audiobooks.core.player

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.auth.SessionStore

/**
 * App start for the player: sends progress, bookmarks and learned durations that could not be sent last time, and wipes the player's data of an account
 * whose session ended (like `SessionCleanup` does for the rest: nothing of an account stays on the phone).
 */
@Singleton
class PlayerStarter @Inject constructor(
    private val sessions: SessionStore,
    private val dao: PlayerDao,
    private val sync: ProgressSync,
    private val bookmarks: BookmarkRepository,
    private val durations: DurationReporter,
    private val hub: PlayerHub,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            var previous: String? = null
            var first = true
            sessions.session.map { it?.userId }.distinctUntilChanged().collect { current ->
                val ended = current == null || (previous != null && current != previous)
                if (ended && !(first && current != null)) wipe()
                if (current != null) {
                    launch {
                        sync.flush(current)
                        bookmarks.sync(null)
                        durations.flush()
                    }
                }
                previous = current
                first = false
            }
        }
    }

    suspend fun wipe() {
        dao.clearProgress()
        dao.clearPrefs()
        dao.clearBookmarks()
        hub.reset()
    }
}
