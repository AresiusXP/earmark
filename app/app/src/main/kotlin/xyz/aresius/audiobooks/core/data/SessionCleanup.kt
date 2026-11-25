package xyz.aresius.audiobooks.core.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.auth.SessionStore

/**
 * Nothing of an account stays on the phone once its session is gone (sign-out, revoked device, rejected refresh) or another user signs in:
 * cached answers, the library copy, the cached profile, and the "onboarding done" flag (the next sign-in goes through the steps again).
 */
@Singleton
class SessionCleanup(
    private val sessions: SessionStore,
    private val cache: CacheDao,
    private val library: LibraryDao,
    private val me: MeRepository,
    private val prefs: AppPreferences,
) {
    @Inject
    constructor(sessions: SessionStore, db: AudiobooksDb, me: MeRepository, prefs: AppPreferences) :
        this(sessions, db.cacheDao(), db.libraryDao(), me, prefs)

    fun start(scope: CoroutineScope) {
        scope.launch {
            var previous: String? = null
            var first = true
            sessions.session.map { it?.userId }.distinctUntilChanged().collect { current ->
                // At start-up a signed-in user keeps their data; a signed-out start (e.g. after a kill during sign-out) is cleaned.
                if (first && current != null) {
                    previous = current
                } else if (current == null || (previous != null && current != previous)) {
                    wipe()
                    previous = current
                } else {
                    previous = current
                }
                first = false
            }
        }
    }

    suspend fun wipe() {
        cache.clear()
        library.clear()
        me.clear()
        prefs.setOnboardingDone(false)
    }
}
