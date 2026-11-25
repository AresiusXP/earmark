package xyz.aresius.audiobooks.core.player

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ErrorCode
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Prepare
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.apiErrorFrom

/** Why a stream could not be opened; every kind is an [IOException] so ExoPlayer treats it as a load error. */
sealed class StreamException(message: String, val serverMessage: String? = null) : IOException(message) {
    /** 202: the provider is still fetching the file. */
    class Preparing(val prepare: Prepare) : StreamException("preparing")

    /** 403 `no_debrid_key` ([badKey] false) or `bad_debrid_key` (true). */
    class NeedsKey(val badKey: Boolean, serverMessage: String?) : StreamException(if (badKey) "bad_debrid_key" else "no_debrid_key", serverMessage)

    /** 401 that the token refresh did not fix. */
    class Unauthorized : StreamException("unauthorized")
    class NotFound(serverMessage: String?) : StreamException("not_found", serverMessage)
    class RateLimited(val retryAfterS: Int?) : StreamException("rate_limited")

    /** 502 `upstream_failed` and other server answers; the server's text names the reason. */
    class Failed(val status: Int, serverMessage: String?) : StreamException("http $status", serverMessage)

    class NotSignedIn : StreamException("not signed in")
}

/**
 * Turns a release file into the provider's CDN link: `GET /v1/releases/{hash}/files/{index}/download?provider=` with the app's
 * authenticated client (which sends the token to the server's host only and does not follow redirects) and reads the `Location` of the 302.
 * The CDN hop is then a plain request by the data source with NO token (see [PlaybackDataSources]). The link is used at once, never stored.
 * Blocking: it runs on ExoPlayer's loader thread.
 */
class StreamLinkResolver(
    private val client: () -> OkHttpClient,
    private val serverUrl: () -> String?,
) {
    @Throws(IOException::class)
    fun resolve(hash: String, index: Int, provider: Provider): String {
        val base = serverUrl()?.trimEnd('/')?.plus("/")?.toHttpUrlOrNull() ?: throw StreamException.NotSignedIn()
        val url = base.newBuilder()
            .addPathSegments("v1/releases/$hash/files/$index/download")
            .addQueryParameter("provider", provider.wire)
            .build()
        client().newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = runCatching { response.body.string() }.getOrDefault("")
            return when (response.code) {
                301, 302, 303, 307, 308 -> {
                    val location = response.header("Location") ?: throw StreamException.Failed(response.code, "redirect without Location")
                    url.resolve(location)?.toString() ?: throw StreamException.Failed(response.code, "bad redirect")
                }
                202 -> throw StreamException.Preparing(
                    runCatching { AudiobooksJson.decodeFromString(Prepare.serializer(), body) }.getOrElse { Prepare(Prepare.PREPARING) },
                )
                401 -> throw StreamException.Unauthorized()
                else -> throw failure(apiErrorFrom(response.code, body, response.header("Retry-After")?.trim()?.toIntOrNull()))
            }
        }
    }

    private fun failure(e: ApiError.Http): StreamException = when {
        e.status == 403 && e.code == ErrorCode.BadDebridKey -> StreamException.NeedsKey(badKey = true, e.message)
        e.status == 403 && e.code == ErrorCode.NoDebridKey -> StreamException.NeedsKey(badKey = false, e.message)
        e.status == 404 -> StreamException.NotFound(e.message)
        e.status == 429 -> StreamException.RateLimited(e.retryAfterSec)
        else -> StreamException.Failed(e.status, e.message)
    }
}
