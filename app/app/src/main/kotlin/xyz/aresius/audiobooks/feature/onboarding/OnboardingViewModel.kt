package xyz.aresius.audiobooks.feature.onboarding

import android.content.Context
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
import xyz.aresius.audiobooks.core.auth.CodeFormat
import xyz.aresius.audiobooks.core.auth.GoogleAuthRepository
import xyz.aresius.audiobooks.core.auth.GoogleOutcome
import xyz.aresius.audiobooks.core.auth.GoogleTokenRequester
import xyz.aresius.audiobooks.core.auth.GoogleTokenSource
import xyz.aresius.audiobooks.core.auth.InputProblem
import xyz.aresius.audiobooks.core.auth.InputValidation
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.auth.SignInMethod
import xyz.aresius.audiobooks.core.auth.UrlValidation
import xyz.aresius.audiobooks.core.auth.requester
import xyz.aresius.audiobooks.core.data.AppPreferences

enum class OnboardingStep { Server, Method, Details, Debrid, BitTorrent }

/** [GoogleJoin]: an invite code plus a Google account (reached from "Continue with Google" when the account is not linked yet). */
enum class OnboardingMethod { Invite, FirstSetup, Link, GoogleJoin }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Server,
    /** Empty until the user types one: the app ships with no default server. An already-saved one (or the signed-in session's) is prefilled. */
    val serverUrl: String = "",
    /** [serverUrl] passes the URL validation; "Continue" on the server step stays disabled until it does. */
    val serverUrlValid: Boolean = false,
    val checkingServer: Boolean = false,
    /** From `GET /v1/version`: false = nobody has set the instance up yet, so "First setup" is the way in. Null until checked. */
    val bootstrapped: Boolean? = null,
    /** From `GET /v1/version`: the server has Sign in with Google switched on. */
    val googleEnabled: Boolean = false,
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

    /** The secret is an invite or link code (26 base32 characters) rather than the instance password. */
    val secretIsCode: Boolean get() = method != OnboardingMethod.FirstSetup
}

/**
 * Server URL -> how to join -> name + device -> signed in -> Debrid step -> BitTorrent step -> done. The two last steps are skippable and
 * are resumed after a process kill (a session without the "onboarding done" flag starts at the Debrid step).
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val googleAuth: GoogleAuthRepository,
    private val googleSource: GoogleTokenSource,
    private val sessions: SessionStore,
    private val prefs: AppPreferences,
    buildInfo: BuildInfo,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState(deviceName = buildInfo.deviceModel, showDemoShortcut = buildInfo.debugTools))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val session = sessions.load()
            val url = prefs.serverUrl.first().ifBlank { session?.serverUrl.orEmpty() }
            if (url.isNotEmpty()) _state.update { if (it.serverUrl.isEmpty()) it.withServerUrl(url) else it }
            if (session != null && !prefs.onboardingDone.first()) _state.update { it.copy(step = OnboardingStep.Debrid) }
        }
    }

    private fun OnboardingUiState.withServerUrl(text: String) =
        copy(serverUrl = text, serverUrlValid = auth.validateServerUrl(text) is UrlValidation.Valid, error = null)

    fun onServerUrlChange(text: String) = _state.update { it.withServerUrl(text) }

    fun onSecretChange(text: String) = _state.update { it.copy(secret = text, error = null) }

    fun onDisplayNameChange(text: String) = _state.update { it.copy(displayName = text, error = null) }

    fun onDeviceNameChange(text: String) = _state.update { it.copy(deviceName = text, error = null) }

    /** Debug builds: points the app at the in-process FakeBackend (see app/README.md). */
    fun useDemoServer() = _state.update { it.withServerUrl(DEMO_SERVER_URL) }

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
                        googleEnabled = r.value.googleEnabled,
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
            OnboardingMethod.GoogleJoin -> return // joinWithGoogle: the Google sheet needs the Activity
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

    /** Whether this device can show Google's account sheet at all (Google Play services); the card is replaced by an explanation if not. */
    fun googleAvailable(context: Context): Boolean = googleSource.isAvailable(context)

    /** "Continue with Google": nonce -> Google account sheet -> ID token -> sign in. [context] must be the Activity. */
    fun continueWithGoogle(context: Context) = continueWithGoogle(googleSource.requester(context))

    /** "Join with Google" on the details step: invite code + Google account, a new user. */
    fun joinWithGoogle(context: Context) = joinWithGoogle(googleSource.requester(context))

    internal fun continueWithGoogle(requester: GoogleTokenRequester) {
        val s = _state.value
        if (s.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { handleGoogle(googleAuth.signIn(s.serverUrl, s.deviceName, requester)) }
    }

    internal fun joinWithGoogle(requester: GoogleTokenRequester) {
        val s = _state.value
        if (s.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { handleGoogle(googleAuth.join(s.serverUrl, s.secret, s.displayName, s.deviceName, requester)) }
    }

    private fun handleGoogle(outcome: GoogleOutcome) = _state.update {
        when (outcome) {
            is GoogleOutcome.Done -> it.copy(busy = false, secret = "", step = OnboardingStep.Debrid)
            // The account is unknown: the way in is an invite code. The name prefill is the Google given name (the server does the same
            // when none is sent); the code field starts empty and a fresh Google sheet follows on "Join with Google".
            is GoogleOutcome.NotLinked -> it.copy(
                busy = false,
                method = OnboardingMethod.GoogleJoin,
                step = OnboardingStep.Details,
                secret = "",
                displayName = it.displayName.ifBlank { outcome.givenName.orEmpty().trim().take(InputValidation.MAX_DISPLAY_NAME) },
                error = AuthError.GoogleNotLinked,
            )
            GoogleOutcome.Cancelled -> it.copy(busy = false)
            is GoogleOutcome.Failed -> it.copy(busy = false, error = outcome.error)
        }
    }

    /** A code arrived by paste: a valid one is shown in blocks, anything else as it came so the user sees what is wrong. */
    fun onCodePasted(text: String) = _state.update {
        val code = CodeFormat.parse(text)
        it.copy(secret = if (code != null) CodeFormat.grouped(code, "-") else text.trim(), error = if (code == null && text.isNotBlank()) AuthError.InvalidInput(InputProblem.CodeInvalid) else null)
    }

    fun onScanResult(result: ScanResult) = when (result) {
        is ScanResult.Text -> onCodePasted(result.value)
        ScanResult.Cancelled -> Unit
        ScanResult.Unavailable -> _state.update { it.copy(error = AuthError.ScannerUnavailable) }
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
