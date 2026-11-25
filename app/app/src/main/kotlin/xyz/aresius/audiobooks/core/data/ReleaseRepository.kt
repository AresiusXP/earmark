package xyz.aresius.audiobooks.core.data

import javax.inject.Inject
import javax.inject.Singleton
import xyz.aresius.audiobooks.core.network.ApiProvider
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.AudiobooksApi
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Prepare
import xyz.aresius.audiobooks.core.network.PrepareRequest
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.TorrentFile
import xyz.aresius.audiobooks.core.network.TorrentFilesRequest
import xyz.aresius.audiobooks.core.network.safeApiCall

/** What `GET /v1/releases/{hash}/files/{index}/download` answered. */
sealed interface DownloadLink {
    /** 302: use [url] right now (short-lived CDN link). Never store it, never log it. */
    data class Redirect(val url: String) : DownloadLink {
        override fun toString() = "Redirect"
    }

    /** 202: the provider is still preparing the file. */
    data class NotReady(val prepare: Prepare) : DownloadLink
}

/** Preparing and fetching release files. No screen uses it yet; the player/download phases build on it. */
@Singleton
class ReleaseRepository(private val apiSource: suspend () -> AudiobooksApi) {
    @Inject
    constructor(provider: ApiProvider) : this({ provider.api() })

    suspend fun prepare(infoHash: String, provider: Provider, magnet: String? = null): ApiResult<Prepare> =
        safeApiCall { apiSource().prepareRelease(infoHash, PrepareRequest(provider.wire, magnet)) }

    suspend fun downloadLink(infoHash: String, index: Int, provider: Provider): ApiResult<DownloadLink> = safeApiCall {
        val response = apiSource().downloadFile(infoHash, index, provider.wire)
        when (response.code()) {
            302 -> DownloadLink.Redirect(response.headers()["Location"] ?: throw java.io.IOException("redirect without Location"))
            202 -> DownloadLink.NotReady(AudiobooksJson.decodeFromString(Prepare.serializer(), response.body()?.string().orEmpty()))
            else -> throw retrofit2.HttpException(response)
        }
    }

    suspend fun reportTorrentFiles(infoHash: String, files: List<TorrentFile>): ApiResult<List<TorrentFile>> =
        safeApiCall { apiSource().reportTorrentFiles(infoHash, TorrentFilesRequest(files)).torrentFiles }
}
