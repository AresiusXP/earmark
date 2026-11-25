package xyz.aresius.audiobooks.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.data.AppPreferences
import xyz.aresius.audiobooks.core.data.MeRepository

sealed interface AppState {
    data object Loading : AppState

    /** Signed out, or signed in but the Debrid / BitTorrent steps are not finished yet. */
    data object Onboarding : AppState
    data object Main : AppState
}

@HiltViewModel
class AppViewModel @Inject constructor(
    sessions: SessionStore,
    prefs: AppPreferences,
    private val me: MeRepository,
) : ViewModel() {
    val appState: StateFlow<AppState> = combine(sessions.session, prefs.onboardingDone) { session, done ->
        if (session != null && done) AppState.Main else AppState.Onboarding
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppState.Loading)

    /** The opt-in Settings switch for wallpaper colors (default off). */
    val dynamicColor: StateFlow<Boolean> = prefs.dynamicColor.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Shows the admin-only entry in Settings; the server still enforces it (403). */
    val isAdmin: StateFlow<Boolean> = me.me.map { it?.isAdmin == true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // Cold start with a session: the cached profile first (offline), then the server's.
        viewModelScope.launch {
            me.restore()
            if (sessions.load() != null) me.refresh()
        }
    }
}
