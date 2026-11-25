package xyz.aresius.audiobooks.core.network

import java.io.IOException
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import xyz.aresius.audiobooks.core.auth.Session
import xyz.aresius.audiobooks.core.auth.SessionHandle
import xyz.aresius.audiobooks.core.auth.SessionStore

internal fun HttpUrl.sameOrigin(other: HttpUrl) = host == other.host && port == other.port && scheme == other.scheme

private fun Request.isAuthEndpoint() = url.encodedPath.contains("/v1/auth/")

private fun Request.targetsServer(session: Session): Boolean {
    val server = session.serverUrl.toHttpUrlOrNull() ?: return false
    return url.sameOrigin(server)
}

/** Request tag: the sign-in ([SessionHandle.id]) whose token this request carries; a 401 is only ever handled for that same sign-in. */
internal data class SessionBinding(val id: Long)

/**
 * Adds `Authorization: Bearer` to requests for the signed-in server only (never to the CDN or any other host) and binds the request to
 * the sign-in it was made under ([SessionBinding]).
 */
class AuthInterceptor(private val sessions: SessionStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null || request.isAuthEndpoint()) return chain.proceed(request)
        val handle = sessions.handle() ?: return chain.proceed(request)
        if (!request.targetsServer(handle.session)) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer ${handle.session.accessToken}")
                .tag(SessionBinding::class.java, SessionBinding(handle.id))
                .build(),
        )
    }
}

/**
 * Retries on 502/503/504 (a deploy replaces the single pod; the contract says clients retry these) and, for idempotent methods only,
 * on IOExceptions: at most [maxAttempts] attempts, exponential backoff, `Retry-After` (seconds) honoured and capped at [maxRetryAfterMs].
 * A 502/503/504 is a gateway answer: the request did not reach (or was refused by) the handler, so it is also retried for POST/PATCH.
 * No retry starts once [maxTotalMs] since the first attempt would be exceeded by the wait.
 */
class RetryInterceptor(
    private val maxAttempts: Int = 3,
    private val baseDelayMs: Long = 400,
    private val maxRetryAfterMs: Long = 10_000,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val maxTotalMs: Long = Long.MAX_VALUE,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val started = clock()
        var attempt = 1
        while (true) {
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                if (request.method !in IDEMPOTENT || attempt >= maxAttempts || chain.call().isCanceled() || e.isCallTimeout()) throw e
                val wait = backoff(attempt, null)
                if (clock() - started + wait > maxTotalMs) throw e
                sleeper(wait)
                attempt++
                continue
            }
            if (response.code !in RETRYABLE || attempt >= maxAttempts || chain.call().isCanceled()) return response
            val wait = backoff(attempt, response.header("Retry-After"))
            if (clock() - started + wait > maxTotalMs) return response
            response.close()
            sleeper(wait)
            attempt++
        }
    }

    private fun backoff(attempt: Int, retryAfter: String?): Long {
        val fromHeader = retryAfter?.trim()?.toLongOrNull()?.let { (it * 1000).coerceIn(0, maxRetryAfterMs) }
        return fromHeader ?: (baseDelayMs shl (attempt - 1)).coerceAtMost(maxRetryAfterMs)
    }

    private fun IOException.isCallTimeout() = this is java.io.InterruptedIOException && message == "timeout"

    private companion object {
        val IDEMPOTENT = setOf("GET", "HEAD", "PUT", "DELETE", "OPTIONS")
        val RETRYABLE = setOf(502, 503, 504)
    }
}

/**
 * On 401 refreshes the token pair via `POST /v1/auth/refresh`, single-flight: concurrent 401s are serialised and the first one
 * refreshes; the others see the already-rotated token and just retry. The refresh token rotates (the old one dies), so the new pair is
 * persisted before the request is retried. Only ONE refresh per request: a request that already carries a refreshed token and still gets
 * 401 is not retried again. A 401 from the refresh endpoint clears the session and emits a signed-out event (sign-out path).
 *
 * Session identity: every request is bound to the sign-in it was made under ([SessionBinding]). A 401 for a request of an earlier
 * sign-in (another account was signed in meanwhile, or the user signed out) is NOT retried with the current account's token and starts
 * no refresh. A refresh result is stored, and a rejected refresh clears the session, only if that same sign-in is still current
 * ([SessionStore.replaceIfCurrent] / [SessionStore.clearIfCurrent]): a sign-out or user switch during a refresh wins, the late tokens are dropped.
 */
class TokenAuthenticator(
    private val sessions: SessionStore,
    private val refreshClient: OkHttpClient,
    private val signedOut: SignedOutEvents,
    private val now: () -> Long = System::currentTimeMillis,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        val sentHeader = request.header("Authorization") ?: return null
        val binding = request.tag(SessionBinding::class.java) ?: return null
        // A request that already went out again after a 401 is not refreshed for a second time: one refresh per request.
        if (request.isAuthEndpoint() || response.priorResponse?.code == 401 || responseCount(response) >= 3) return null
        synchronized(lock) {
            val handle = sessions.handle() ?: return null
            if (handle.id != binding.id || !request.targetsServer(handle.session)) return null
            val current = "Bearer ${handle.session.accessToken}"
            // Another call of the same sign-in already rotated the token: just retry with it (once: a second 401 with it ends here via responseCount).
            if (sentHeader != current) return request.newBuilder().header("Authorization", current).build()
            val refreshed = refresh(handle) ?: return null
            return request.newBuilder().header("Authorization", "Bearer ${refreshed.accessToken}").build()
        }
    }

    private fun refresh(handle: SessionHandle): Session? {
        val session = handle.session
        val url = session.serverUrl.trimEnd('/').plus("/").toHttpUrlOrNull()?.newBuilder()?.addPathSegments("v1/auth/refresh")?.build() ?: return null
        val body = AudiobooksJson.encodeToString(RefreshRequest.serializer(), RefreshRequest(session.refreshToken))
            .toRequestBody("application/json".toMediaType())
        val call = refreshClient.newCall(Request.Builder().url(url).post(body).build())
        try {
            call.execute().use { r ->
                when {
                    r.isSuccessful -> {
                        val tokens = AudiobooksJson.decodeFromString(SessionResponse.serializer(), r.body.string())
                        val updated = session.copy(
                            accessToken = tokens.accessToken,
                            refreshToken = tokens.refreshToken,
                            expiresAtMs = now() + tokens.expiresIn * 1000,
                        )
                        // Signed out or switched user while the refresh was in flight: the late result is dropped, nothing is restored.
                        return if (sessions.replaceIfCurrentBlocking(handle, updated)) updated else null
                    }
                    r.code == 401 -> {
                        if (sessions.clearIfCurrentBlocking(handle)) signedOut.emit(SignOutReason.RefreshRejected)
                        return null
                    }
                    else -> return null
                }
            }
        } catch (_: IOException) {
            return null
        } catch (_: kotlinx.serialization.SerializationException) {
            return null
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
