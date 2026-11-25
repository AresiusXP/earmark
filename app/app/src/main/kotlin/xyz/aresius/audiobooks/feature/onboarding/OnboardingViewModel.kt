package xyz.aresius.audiobooks.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.auth.AuthError
import xyz.aresius.audiobooks.core.auth.AuthRepository
import xyz.aresius.audiobooks.core.auth.AuthResult
import xyz.aresius.audiobooks.core.auth.BuildInfo
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.auth.SignInMethod
import xyz.aresius.audiobooks.core.data.AppPreferences

enum class OnboardingStep { Server, Method, Details, Debrid, BitTorrent }

enum class OnboardingMethod { Invite, FirstSetup, Link }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Server,
    val serverUrl: String = "",
    val checkingServer: Boolean = false,
    /** From `GET /v1/version`: false = nobody has set the instance up yet, so "First setup" is the way in. Null until checked. */
    val bootstrapped: Boolean? = null,
    val method: OnboardingMethod = OnboardingMethod.Invite,
    /** Invite code, instance password or link code, depending on [method]. */
    val secret: String = "",
    val displayName: String = "",
    val deviceName: String = "",
    val busy: Boolean = false,
    val error: AuthError? = null,
    val showDemoShortcut: Boolean = false,
) {
    val needsDisplayName: Boolean get() = method != OnboardingMethod.Link
}

/**
 * Server URL -> how to join -> name + device -> signed in -> Debrid step -> BitTorrent step -> done. The two last steps are skippable and
 * are resumed after a process kill (a session without the "onboarding done" flag starts at the Debrid step).
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val sessions: SessionStore,
    private val prefs: AppPreferences,
    buildInfo: BuildInfo,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState(deviceName = buildInfo.deviceModel, showDemoShortcut = buildInfo.debugTools))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val url = prefs.serverUrl.first()
            _state.update { it.copy(serverUrl = if (it.serverUrl.isEmpty()) url else it.serverUrl) }
            if (sessions.load() != null && !prefs.onboardingDone.first()) _state.update { it.copy(step = OnboardingStep.Debrid) }
        }
    }

    fun onServerUrlChange(text: String) = _state.update { it.copy(serverUrl = text, error = null) }

    fun onSecretChange(text: String) = _state.update { it.copy(secret = text, error = null) }

    fun onDisplayNameChange(text: String) = _state.update { it.copy(displayName = text, error = null) }

    fun onDeviceNameChange(text: String) = _state.update { it.copy(deviceName = text, error = null) }

    /** Debug builds: points the app at the in-process FakeBackend (see app/README.md). */
    fun useDemoServer() = _state.update { it.copy(serverUrl = DEMO_SERVER_URL, error = null) }

    /** "Continue" on the server step: checks that the URL is an Audiobooks server before going on. */
    fun checkServer() {
        val s = _state.value
        if (s.checkingServer) return
        _state.update { it.copy(checkingServer = true, error = null) }
        viewModelScope.launch {
            when (val r = auth.checkServer(s.serverUrl)) {
                is AuthResult.Success -> _state.update {
                    it.copy(
                        checkingServer = false,
                        bootstrapped = r.value.bootstrapped,
                        method = if (!r.value.bootstrapped) OnboardingMethod.FirstSetup else if (it.method == OnboardingMethod.FirstSetup) OnboardingMethod.Invite else it.method,
                        step = OnboardingStep.Method,
                    )
                }
                is AuthResult.Failure -> _state.update { it.copy(checkingServer = false, error = r.error) }
            }
        }
    }

    fun chooseMethod(method: OnboardingMethod) = _state.update { it.copy(method = method, secret = "", error = null, step = OnboardingStep.Details) }

    fun back() = _state.update {
        val previous = when (it.step) {
            OnboardingStep.Details -> OnboardingStep.Method
            OnboardingStep.Method -> OnboardingStep.Server
            else -> it.step // after sign-in there is no way back to the credentials
        }
        it.copy(step = previous, error = null)
    }

    fun signIn() {
        val s = _state.value
        if (s.busy) return
        val method = when (s.method) {
            OnboardingMethod.Invite -> SignInMethod.Invite(s.secret, s.displayName)
            OnboardingMethod.FirstSetup -> SignInMethod.Bootstrap(s.secret, s.displayName)
            OnboardingMethod.Link -> SignInMethod.Link(s.secret)
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = auth.signIn(s.serverUrl, method, s.deviceName)) {
                // The code is single-use and spent now: never keep it around.
                is AuthResult.Success -> _state.update { it.copy(busy = false, secret = "", step = OnboardingStep.Debrid) }
                is AuthResult.Failure -> _state.update { it.copy(busy = false, error = r.error) }
            }
        }
    }

    /** Debrid step done or skipped. */
    fun debridDone() = _state.update { it.copy(step = OnboardingStep.BitTorrent) }

    /** BitTorrent step done or skipped: onboarding is over and the app shell takes over. */
    fun finish() {
        viewModelScope.launch { prefs.setOnboardingDone(true) }
    }

    companion object {
        /** Debug builds route this host to the in-process FakeBackend (src/debug). */
        const val DEMO_SERVER_URL = "http://demo.audiobooks.test"
    }
}
