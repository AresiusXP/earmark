package xyz.aresius.audiobooks.core.auth

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import xyz.aresius.audiobooks.core.data.AppPreferences
import xyz.aresius.audiobooks.core.data.MeRepository
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiProvider
import xyz.aresius.audiobooks.core.network.AudiobooksApi
import xyz.aresius.audiobooks.core.network.BootstrapRequest
import xyz.aresius.audiobooks.core.network.ErrorCode
import xyz.aresius.audiobooks.core.network.JoinRequest
import xyz.aresius.audiobooks.core.network.LinkRequest
import xyz.aresius.audiobooks.core.network.Me
import xyz.aresius.audiobooks.core.network.SessionResponse
import xyz.aresius.audiobooks.core.network.SignOutReason
import xyz.aresius.audiobooks.core.network.SignedOutEvents
import xyz.aresius.audiobooks.core.network.VersionInfo
import xyz.aresius.audiobooks.core.network.safeApiCall
import xyz.aresius.audiobooks.core.network.toApiError
import xyz.aresius.audiobooks.core.network.ApiResult

/** Why signing in failed; the UI maps each case to a string resource. */
sealed interface AuthError {
    data class InvalidInput(val problem: InputProblem) : AuthError

    /** 401: unknown, used or expired invite / link code, or a wrong instance password. */
    data object BadCredentials : AuthError

    /** 409 on bootstrap: the instance already has users. */
    data object AlreadySetUp : AuthError

    /** Sign in with Google: the server does not know this Google account (join with an invite code). */
    data object GoogleNotLinked : AuthError

    /** The server refused the Google ID token (expired nonce, invalid token) or the user is disabled. */
    data object GoogleRejected : AuthError

    /** 409: the Google account belongs to another user, or this user already has a different one. */
    data object GoogleConflict : AuthError

    /** 404 `google_disabled`: the server has Sign in with Google switched off. */
    data object GoogleDisabled : AuthError

    data object GoogleNoAccount : AuthError

    /** No Google Play services / Credential Manager on this device. */
    data object GoogleUnavailable : AuthError

    data object GoogleFailed : AuthError

    /** The Google code scanner is missing or failed: the user pastes the code instead. */
    data object ScannerUnavailable : AuthError

    data class TooManyAttempts(val retryAfterSec: Int?) : AuthError
    data object Network : AuthError
    data class Server(val status: Int, val message: String?) : AuthError
    data class Unknown(val detail: String?) : AuthError
}

sealed interface AuthResult<out T> {
    data class Success<T>(val value: T) : AuthResult<T>
    data class Failure(val error: AuthError) : AuthResult<Nothing>
}

/** How a new device gets its token pair. */
sealed interface SignInMethod {
    data class Bootstrap(val instancePassword: String, val displayName: String) : SignInMethod
    data class Invite(val code: String, val displayName: String) : SignInMethod
    data class Link(val code: String) : SignInMethod
}

@Singleton
class AuthRepository(
    private val apiProvider: ApiProvider,
    private val sessions: SessionStore,
    private val signedOut: SignedOutEvents,
    private val prefs: AppPreferences,
    private val me: MeRepository,
    private val allowCleartextAnyHost: Boolean,
    private val now: () -> Long,
) {
    @Inject
    constructor(
        apiProvider: ApiProvider,
        sessions: SessionStore,
        signedOut: SignedOutEvents,
        prefs: AppPreferences,
        me: MeRepository,
        buildInfo: BuildInfo,
    ) : this(apiProvider, sessions, signedOut, prefs, me, buildInfo.debugTools, System::currentTimeMillis)

    /** Validates [input] as a server URL; the normalised URL on success. */
    fun validateServerUrl(input: String): UrlValidation = InputValidation.validateUrl(input, allowCleartextAnyHost)

    /** `GET /v1/version`: proves the URL is an Audiobooks server and tells whether the first admin exists. */
    suspend fun checkServer(url: String): AuthResult<VersionInfo> {
        val valid = when (val v = validateServerUrl(url)) {
            is UrlValidation.Valid -> v.url
            is UrlValidation.Invalid -> return AuthResult.Failure(AuthError.InvalidInput(v.problem))
        }
        return when (val r = safeApiCall { apiProvider.unauthenticated(valid).getVersion() }) {
            is ApiResult.Success ->
                if (r.value.apiVersion <= 0) AuthResult.Failure(AuthError.Unknown("not an Audiobooks server")) else AuthResult.Success(r.value)
            is ApiResult.Failure -> AuthResult.Failure(mapError(r.error))
        }
    }

    suspend fun signIn(url: String, method: SignInMethod, deviceName: String): AuthResult<Me> {
        val valid = when (val v = validateServerUrl(url)) {
            is UrlValidation.Valid -> v.url
            is UrlValidation.Invalid -> return AuthResult.Failure(AuthError.InvalidInput(v.problem))
        }
        val device = deviceName.trim()
        InputValidation.validateDeviceName(device)?.let { return AuthResult.Failure(AuthError.InvalidInput(it)) }
        val secret = when (method) {
            is SignInMethod.Bootstrap -> method.instancePassword
            is SignInMethod.Invite -> method.code
            is SignInMethod.Link -> method.code
        }.trim() // a code pasted from a chat often carries a trailing space or newline
        if (secret.isEmpty()) return AuthResult.Failure(AuthError.InvalidInput(InputProblem.SecretEmpty))
        // Invite and link codes are 26 base32 characters; the server folds case, separators and look-alikes the same way.
        val code = if (method is SignInMethod.Bootstrap) secret else CodeFormat.parse(secret) ?: return AuthResult.Failure(AuthError.InvalidInput(InputProblem.CodeInvalid))
        val name = when (method) {
            is SignInMethod.Bootstrap -> method.displayName.trim()
            is SignInMethod.Invite -> method.displayName.trim()
            is SignInMethod.Link -> null
        }
        if (name != null) InputValidation.validateDisplayName(name)?.let { return AuthResult.Failure(AuthError.InvalidInput(it)) }
        return try {
            val api = apiProvider.unauthenticated(valid)
            val response = when (method) {
                is SignInMethod.Bootstrap -> api.bootstrap(BootstrapRequest(code, name!!, device))
                is SignInMethod.Invite -> api.join(JoinRequest(code, name!!, device))
                is SignInMethod.Link -> api.linkDevice(LinkRequest(code, device))
            }
            AuthResult.Success(persist(valid, device, response))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AuthResult.Failure(mapError(e.toApiError()))
        }
    }

    internal suspend fun persist(serverUrl: String, device: String, response: SessionResponse): Me {
        sessions.save(
            Session(
                serverUrl = serverUrl,
                accessToken = response.accessToken,
                refreshToken = response.refreshToken,
                expiresAtMs = now() + response.expiresIn * 1000,
                deviceName = device,
                userId = response.user.id,
            ),
        )
        me.set(response.user)
        prefs.setServerUrl(serverUrl)
        return response.user
    }

    /**
     * Revokes this device on the server (best effort, short bound: a dead network must not keep the user signed in), then forgets the
     * tokens. Everything cached for the account is dropped by [SessionCleanup] reacting to the cleared session.
     */
    suspend fun signOut() {
        // Best effort only: whatever the server says (refuses, 5xx, 401, no network, a hang) the user ends up signed out, and leaving the
        // screen mid-way (cancellation) must not leave the session behind.
        runCatching {
            safeApiCall(timeoutMs = SIGN_OUT_REMOTE_MS) { revokeCurrentDevice(apiProvider.api()) }
        }
        withContext(NonCancellable) {
            sessions.clear()
            signedOut.emit(SignOutReason.UserRequested)
        }
    }

    private suspend fun revokeCurrentDevice(api: AudiobooksApi) {
        val current = api.listDevices().devices.firstOrNull { it.current } ?: return
        api.revokeDevice(current.id)
    }

    private fun mapError(e: ApiError): AuthError = when (e) {
        ApiError.Network -> AuthError.Network
        ApiError.NotSignedIn -> AuthError.BadCredentials
        is ApiError.Unknown -> AuthError.Unknown(e.message)
        is ApiError.Http -> when {
            e.status == 401 || e.code == ErrorCode.Unauthorized -> AuthError.BadCredentials
            e.status == 409 || e.code == ErrorCode.Conflict -> AuthError.AlreadySetUp
            e.status == 429 || e.code == ErrorCode.RateLimited -> AuthError.TooManyAttempts(e.retryAfterSec)
            else -> AuthError.Server(e.status, e.message)
        }
    }

    private companion object {
        const val SIGN_OUT_REMOTE_MS = 6_000L
    }
}

