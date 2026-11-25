package xyz.aresius.audiobooks.debug

import android.content.Context
import xyz.aresius.audiobooks.core.auth.DebugGoogleTokenSource
import xyz.aresius.audiobooks.core.auth.GoogleCredential
import xyz.aresius.audiobooks.core.auth.GoogleTokenResult

/** A "Google ID token" of the demo backend: not signed, only understood by [FakeBackend]. */
class FakeGoogleToken(val sub: String, val email: String, val givenName: String, val nonce: String) {
    fun encode() = listOf(PREFIX, sub, email, givenName, nonce).joinToString("|")

    companion object {
        private const val PREFIX = "fake-google-token"

        fun parse(text: String): FakeGoogleToken? {
            val p = text.split('|')
            return if (p.size == 5 && p[0] == PREFIX) FakeGoogleToken(p[1], p[2], p[3], p[4]) else null
        }
    }
}

/**
 * Debug builds: stands in for Credential Manager when the server is the demo backend (its nonces start with `demo-nonce-`), so the whole
 * Google flow (sign in, not linked -> join, link, unlink, conflicts) is testable on an emulator without a Google account.
 */
class FakeGoogleTokenSource(private val account: FakeGoogleAccount = FakeGoogleAccount()) : DebugGoogleTokenSource {
    override fun isAvailable(context: Context) = true

    override fun handles(nonce: String) = nonce.startsWith("demo-nonce-")

    override suspend fun request(context: Context, nonce: String): GoogleTokenResult {
        val token = FakeGoogleToken(account.sub, account.email, account.givenName, nonce).encode()
        return GoogleTokenResult.Token(GoogleCredential(token, account.givenName, account.email))
    }
}

data class FakeGoogleAccount(
    val sub: String = "demo-google-sub-1",
    val email: String = "demo.listener@example.test",
    val givenName: String = "Demo",
)
