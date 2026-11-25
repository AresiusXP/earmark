package xyz.aresius.audiobooks.core.network

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import xyz.aresius.audiobooks.core.auth.SessionStore

/** Ensures exactly one trailing slash (Retrofit requirement). */
fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/') + "/"

data class NetworkTimeouts(
    val connectMs: Long = CONNECT_MS,
    val readMs: Long = READ_MS,
    val writeMs: Long = WRITE_MS,
    /** Whole call, retries and their backoff included. */
    val callMs: Long = CALL_MS,
) {
    companion object {
        const val CONNECT_MS = 10_000L
        const val READ_MS = 15_000L
        const val WRITE_MS = 15_000L
        const val CALL_MS = 40_000L

        /** The most a suspending API call may take before the caller gives up (a little above [CALL_MS]: the HTTP layer normally fails first). */
        const val API_CALL_MS = 45_000L
    }
}

/** Interceptors only debug builds add (the in-process FakeBackend, see `src/debug`); empty in release builds. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DebugInterceptors

/**
 * The one place that builds OkHttp clients:
 *  - [api]: auth header (signed-in server only), retry on 502/503/504, 401 -> refresh once -> sign out; never follows redirects
 *    (the download route answers 302 and the app must read `Location` itself)
 *  - [plain]: retry only, no auth (onboarding calls, the refresh call, images)
 */
@Singleton
class HttpClientFactory(
    private val sessions: SessionStore,
    private val signedOut: SignedOutEvents,
    private val timeouts: NetworkTimeouts,
    private val debugInterceptors: List<Interceptor>,
) {
    @Inject
    constructor(sessions: SessionStore, signedOut: SignedOutEvents, @DebugInterceptors debugInterceptors: @JvmSuppressWildcards List<Interceptor>) :
        this(sessions, signedOut, NetworkTimeouts(), debugInterceptors)

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.readMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeouts.writeMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeouts.callMs, TimeUnit.MILLISECONDS)
            .apply { debugInterceptors.forEach(::addInterceptor) }
            .build()
    }

    private val plainClient: OkHttpClient by lazy { base.newBuilder().addInterceptor(retry()).build() }

    private val apiClient: OkHttpClient by lazy {
        base.newBuilder()
            .addInterceptor(retry())
            .addInterceptor(AuthInterceptor(sessions))
            .authenticator(TokenAuthenticator(sessions, plainClient, signedOut))
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private fun retry() = RetryInterceptor(maxTotalMs = timeouts.callMs)

    fun api(): OkHttpClient = apiClient

    fun plain(): OkHttpClient = plainClient
}

/** Builds Retrofit [AudiobooksApi] instances; the server URL is user-provided so it is read from the session. */
@Singleton
class ApiProvider @Inject constructor(
    private val sessions: SessionStore,
    private val clients: HttpClientFactory,
) {
    private val cache = HashMap<String, AudiobooksApi>()

    /** API for the signed-in server (authenticated). Throws [NotSignedInException] when signed out. */
    suspend fun api(): AudiobooksApi {
        val session = sessions.load() ?: throw NotSignedInException()
        return build(session.serverUrl, authenticated = true)
    }

    /** Unauthenticated API for an explicit URL, used by onboarding before a session exists. */
    fun unauthenticated(serverUrl: String): AudiobooksApi = build(serverUrl, authenticated = false)

    @Synchronized
    private fun build(serverUrl: String, authenticated: Boolean): AudiobooksApi {
        val base = normalizeBaseUrl(serverUrl)
        return cache.getOrPut("$authenticated|$base") {
            Retrofit.Builder()
                .baseUrl(base)
                .client(if (authenticated) clients.api() else clients.plain())
                .addConverterFactory(AudiobooksJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(AudiobooksApi::class.java)
        }
    }
}
