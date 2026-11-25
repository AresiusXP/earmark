package xyz.aresius.audiobooks.core.auth

import android.content.Context
import xyz.aresius.audiobooks.core.network.Me

/**
 * What Credential Manager gave back. [idToken] is a bearer credential: it is only ever sent to the server (never logged, never stored),
 * so [toString] leaves it out.
 */
class GoogleCredential(val idToken: String, val givenName: String? = null, val email: String? = null) {
    override fun toString() = "GoogleCredential()"
}

sealed interface GoogleTokenResult {
    class Token(val credential: GoogleCredential) : GoogleTokenResult {
        override fun toString() = "Token()"
    }

    /** The user closed the sheet or tapped outside it: not an error, nothing is shown. */
    data object Cancelled : GoogleTokenResult

    /** No Google account on this device (or none the user chose). */
    data object NoAccount : GoogleTokenResult

    /** No Google Play services / Credential Manager provider: the Google path cannot work here at all. */
    data object Unavailable : GoogleTokenResult

    data class Failed(val reason: String? = null) : GoogleTokenResult
}

/** Gets one Google ID token carrying [nonce]. [Context] must be an Activity (it hosts the account sheet). */
interface GoogleTokenSource {
    fun isAvailable(context: Context): Boolean
    suspend fun request(context: Context, nonce: String): GoogleTokenResult
}

/** [GoogleTokenSource] with the Activity already bound: what the repositories and view models work with (JVM-testable). */
fun interface GoogleTokenRequester {
    suspend fun request(nonce: String): GoogleTokenResult
}

fun GoogleTokenSource.requester(context: Context) = GoogleTokenRequester { nonce -> request(context, nonce) }

/** Debug builds only: a fake Google that answers nonces the demo backend minted ([handles]) and never opens a sheet. */
interface DebugGoogleTokenSource : GoogleTokenSource {
    fun handles(nonce: String): Boolean
}

/** Routes the demo backend's nonces to the debug fake; everything else goes to the real Credential Manager. */
class RoutingGoogleTokenSource(private val real: GoogleTokenSource, private val debug: List<DebugGoogleTokenSource>) : GoogleTokenSource {
    override fun isAvailable(context: Context) = debug.any { it.isAvailable(context) } || real.isAvailable(context)

    override suspend fun request(context: Context, nonce: String): GoogleTokenResult =
        (debug.firstOrNull { it.handles(nonce) } ?: real).request(context, nonce)
}

sealed interface GoogleOutcome {
    /** Signed in (or linked): the user as the server now sees it. */
    data class Done(val me: Me) : GoogleOutcome

    /** 401 `google_not_linked`: the server does not know this Google account. [givenName] prefills the join form. */
    data class NotLinked(val givenName: String?) : GoogleOutcome

    /** The user backed out. Show nothing. */
    data object Cancelled : GoogleOutcome

    data class Failed(val error: AuthError) : GoogleOutcome
}
