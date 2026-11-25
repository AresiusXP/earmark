package xyz.aresius.audiobooks.core.auth

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import xyz.aresius.audiobooks.core.data.MeRepository
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ApiProvider
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.AudiobooksApi
import xyz.aresius.audiobooks.core.network.ErrorCode
import xyz.aresius.audiobooks.core.network.GoogleJoinRequest
import xyz.aresius.audiobooks.core.network.GoogleLink
import xyz.aresius.audiobooks.core.network.GoogleLinkRequest
import xyz.aresius.audiobooks.core.network.GoogleSignInRequest
import xyz.aresius.audiobooks.core.network.safeApiCall
import xyz.aresius.audiobooks.core.network.toApiError

/**
 * Sign in with Google, link and unlink. Every attempt is complete in itself: nonce -> ID token -> request. The server spends a nonce as soon
 * as its token verified, so after ANY failed attempt the next one starts again at the nonce AND asks Credential Manager for a new ID token;
 * nothing of an attempt (nonce, token) is kept or reused. Google never creates an account: an unknown Google account is
 * [GoogleOutcome.NotLinked] and the user can [join] with an invite code.
 */
@Singleton
class GoogleAuthRepository @Inject constructor(
    private val apiProvider: ApiProvider,
    private val auth: AuthRepository,
    private val me: MeRepository,
) {
    /** `POST /v1/auth/google`: a new device of the user this Google account is linked to. */
    suspend fun signIn(serverUrl: String, deviceName: String, requester: GoogleTokenRequester): GoogleOutcome {
        val (url, device) = validated(serverUrl, deviceName) ?: return invalid(serverUrl, deviceName)
        val api = apiProvider.unauthenticated(url)
        val credential = when (val a = acquire(api, requester)) {
            is Acquired.Ok -> a.credential
            is Acquired.Stop -> return a.outcome
        }
        return try {
            GoogleOutcome.Done(auth.persist(url, device, api.googleSignIn(GoogleSignInRequest(credential.idToken, device))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val error = e.toGoogleError(join = false)
            if (error == AuthError.GoogleNotLinked) GoogleOutcome.NotLinked(credential.givenName) else GoogleOutcome.Failed(error)
        }
    }

    /** `POST /v1/auth/google/join`: a new user from an invite code plus this Google account (one transaction on the server). */
    suspend fun join(serverUrl: String, inviteCode: String, displayName: String, deviceName: String, requester: GoogleTokenRequester): GoogleOutcome {
        val (url, device) = validated(serverUrl, deviceName) ?: return invalid(serverUrl, deviceName)
        val code = CodeFormat.parse(inviteCode)
            ?: return GoogleOutcome.Failed(AuthError.InvalidInput(if (inviteCode.isBlank()) InputProblem.SecretEmpty else InputProblem.CodeInvalid))
        val name = displayName.trim()
        InputValidation.validateDisplayName(name)?.let { return GoogleOutcome.Failed(AuthError.InvalidInput(it)) }
        val api = apiProvider.unauthenticated(url)
        val credential = when (val a = acquire(api, requester)) {
            is Acquired.Ok -> a.credential
            is Acquired.Stop -> return a.outcome
        }
        return try {
            GoogleOutcome.Done(auth.persist(url, device, api.googleJoin(GoogleJoinRequest(credential.idToken, code, name, device))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            GoogleOutcome.Failed(e.toGoogleError(join = true))
        }
    }

    /** `POST /v1/me/google` for the signed-in user. */
    suspend fun link(requester: GoogleTokenRequester): GoogleOutcome {
        val api = try {
            apiProvider.api()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return GoogleOutcome.Failed(AuthError.BadCredentials)
        }
        val credential = when (val a = acquire(api, requester)) {
            is Acquired.Ok -> a.credential
            is Acquired.Stop -> return a.outcome
        }
        return when (val r = safeApiCall { api.linkGoogle(GoogleLinkRequest(credential.idToken)) }) {
            is ApiResult.Success -> GoogleOutcome.Done(r.value.also { me.set(it) })
            is ApiResult.Failure -> GoogleOutcome.Failed(r.error.toGoogleError(join = false))
        }
    }

    /** `DELETE /v1/me/google`: always allowed, idempotent. */
    suspend fun unlink(): ApiResult<Unit> {
        val r = safeApiCall { apiProvider.api().unlinkGoogle() }
        if (r is ApiResult.Success) me.me.value?.let { me.set(it.copy(google = GoogleLink())) }
        return r
    }

    /** Whether the signed-in server offers Google (`GET /v1/version`); false when it cannot be told. */
    suspend fun serverOffersGoogle(): Boolean =
        (safeApiCall { apiProvider.api().getVersion() } as? ApiResult.Success)?.value?.googleEnabled == true

    private sealed interface Acquired {
        class Ok(val credential: GoogleCredential) : Acquired
        class Stop(val outcome: GoogleOutcome) : Acquired
    }

    private suspend fun acquire(api: AudiobooksApi, requester: GoogleTokenRequester): Acquired {
        val nonce = when (val r = safeApiCall { api.googleNonce() }) {
            is ApiResult.Success -> r.value.nonce
            is ApiResult.Failure -> return Acquired.Stop(GoogleOutcome.Failed(r.error.toGoogleError(join = false)))
        }
        val result = try {
            requester.request(nonce)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            GoogleTokenResult.Failed(e.javaClass.simpleName) // class name only: never the message, it could echo the token
        }
        return when (result) {
            is GoogleTokenResult.Token -> Acquired.Ok(result.credential)
            GoogleTokenResult.Cancelled -> Acquired.Stop(GoogleOutcome.Cancelled)
            GoogleTokenResult.NoAccount -> Acquired.Stop(GoogleOutcome.Failed(AuthError.GoogleNoAccount))
            GoogleTokenResult.Unavailable -> Acquired.Stop(GoogleOutcome.Failed(AuthError.GoogleUnavailable))
            is GoogleTokenResult.Failed -> Acquired.Stop(GoogleOutcome.Failed(AuthError.GoogleFailed))
        }
    }

    private fun validated(serverUrl: String, deviceName: String): Pair<String, String>? {
        val url = (auth.validateServerUrl(serverUrl) as? UrlValidation.Valid)?.url ?: return null
        val device = deviceName.trim()
        return if (InputValidation.validateDeviceName(device) == null) url to device else null
    }

    private fun invalid(serverUrl: String, deviceName: String): GoogleOutcome {
        val problem = (auth.validateServerUrl(serverUrl) as? UrlValidation.Invalid)?.problem
            ?: InputValidation.validateDeviceName(deviceName.trim())
            ?: InputProblem.UrlInvalid
        return GoogleOutcome.Failed(AuthError.InvalidInput(problem))
    }

    private fun Throwable.toGoogleError(join: Boolean): AuthError = toApiError().toGoogleError(join)

    private fun ApiError.toGoogleError(join: Boolean): AuthError = when (this) {
        ApiError.Network -> AuthError.Network
        ApiError.NotSignedIn -> AuthError.BadCredentials
        is ApiError.Unknown -> AuthError.Unknown(message)
        is ApiError.Http -> when {
            code == ErrorCode.GoogleNotLinked && !join -> AuthError.GoogleNotLinked
            code == ErrorCode.GoogleDisabled || status == 404 -> AuthError.GoogleDisabled
            status == 409 || code == ErrorCode.Conflict -> AuthError.GoogleConflict
            status == 429 || code == ErrorCode.RateLimited -> AuthError.TooManyAttempts(retryAfterSec)
            // On join a 401 is an unknown/used/expired invite or a token the server refused: the screen says both.
            status == 401 || code == ErrorCode.Unauthorized -> if (join) AuthError.BadCredentials else AuthError.GoogleRejected
            else -> AuthError.Server(status, message)
        }
    }
}
