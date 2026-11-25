package xyz.aresius.audiobooks.testutil

import android.content.Context
import xyz.aresius.audiobooks.core.auth.GoogleAuthRepository
import xyz.aresius.audiobooks.core.auth.GoogleCredential
import xyz.aresius.audiobooks.core.auth.GoogleTokenRequester
import xyz.aresius.audiobooks.core.auth.GoogleTokenResult
import xyz.aresius.audiobooks.core.auth.GoogleTokenSource

fun TestApp.googleAuth() = GoogleAuthRepository(provider, auth(), me)

/** For view models that only keep the source around (tests drive the flow through a [GoogleTokenRequester]). */
class UnusedTokenSource(private val available: Boolean = true) : GoogleTokenSource {
    override fun isAvailable(context: Context) = available
    override suspend fun request(context: Context, nonce: String): GoogleTokenResult = error("tests use a requester")
}

/**
 * A scripted Google: each call to [request] answers the next script entry (the last repeats) and records the nonce it was asked with.
 * The default entry is a token that names the nonce, so a test can tell which nonce a server call carried.
 */
class ScriptedGoogle(vararg script: (String) -> GoogleTokenResult) : GoogleTokenRequester {
    private val entries = script.toMutableList().ifEmpty { mutableListOf(token()) }
    val nonces = ArrayList<String>()

    override suspend fun request(nonce: String): GoogleTokenResult {
        nonces.add(nonce)
        val entry = if (entries.size > 1) entries.removeAt(0) else entries[0]
        return entry(nonce)
    }

    companion object {
        /** "id-token-for-<nonce>" so every ID token is distinguishable (never a real token). */
        fun token(given: String? = "Grace", email: String? = "grace@example.test"): (String) -> GoogleTokenResult =
            { nonce -> GoogleTokenResult.Token(GoogleCredential("id-token-for-$nonce", given, email)) }

        fun cancelled(): (String) -> GoogleTokenResult = { GoogleTokenResult.Cancelled }
        fun result(r: GoogleTokenResult): (String) -> GoogleTokenResult = { r }
    }
}
