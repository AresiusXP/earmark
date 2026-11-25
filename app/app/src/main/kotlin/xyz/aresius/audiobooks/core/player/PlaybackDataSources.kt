package xyz.aresius.audiobooks.core.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.network.HttpClientFactory
import xyz.aresius.audiobooks.core.network.Provider

/** `audiobook://<hash>/<index>?provider=<wire>`: a release file to stream. Local files use plain `file://` URIs. */
object StreamUri {
    const val SCHEME = "audiobook"

    fun of(hash: String, index: Int, provider: Provider): Uri =
        Uri.Builder().scheme(SCHEME).authority(hash).appendPath(index.toString()).appendQueryParameter("provider", provider.wire).build()
}

/**
 * The data sources of the player: `file://` (downloaded files) and https straight through ExoPlayer's defaults, and `audiobook://`
 * resolved by [StreamLinkResolver] to the CDN link, which a PLAIN OkHttp client (no auth interceptor: the token never reaches the CDN)
 * then reads with Range requests (it follows the CDN's redirects). Streams have no call timeout (a book is listened to for hours), only a read timeout.
 */
@Singleton
class PlaybackDataSources(
    private val context: Context,
    private val resolver: StreamLinkResolver,
    private val cdnClient: () -> OkHttpClient,
) {
    @Inject
    constructor(@ApplicationContext context: Context, clients: HttpClientFactory, sessions: SessionStore) : this(
        context,
        StreamLinkResolver({ clients.api() }, { sessions.snapshot()?.serverUrl }),
        { clients.cdn() },
    )

    fun factory(): DataSource.Factory {
        val http = OkHttpDataSource.Factory(
            cdnClient().newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
        ).setUserAgent("Audiobooks")
        val upstream = DefaultDataSource.Factory(context, http)
        return ResolvingDataSource.Factory(upstream) { spec -> resolve(spec) }
    }

    private fun resolve(spec: DataSpec): DataSpec {
        val uri = spec.uri
        if (uri.scheme != StreamUri.SCHEME) return spec
        val hash = uri.host.orEmpty()
        val index = uri.pathSegments.firstOrNull()?.toIntOrNull() ?: throw StreamException.NotFound("bad stream uri")
        val provider = Provider.fromWire(uri.getQueryParameter("provider")) ?: throw StreamException.NotFound("bad provider")
        return spec.withUri(Uri.parse(resolver.resolve(hash, index, provider)))
    }
}

/**
 * ExoPlayer's default retries (a flaky connection is common on a phone), except for answers a retry cannot change: "preparing", a missing
 * or rejected key and the like fail at once so the service can show them and act (poll `prepare`, ask for a key).
 */
class PlaybackLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(MIN_RETRIES) {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = info.exception
        if (e is StreamException && e !is StreamException.RateLimited) return androidx.media3.common.C.TIME_UNSET
        return super.getRetryDelayMsFor(info)
    }

    private companion object {
        const val MIN_RETRIES = 5
    }
}
