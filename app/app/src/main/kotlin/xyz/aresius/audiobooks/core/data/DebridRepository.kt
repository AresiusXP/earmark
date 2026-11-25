package xyz.aresius.audiobooks.core.data

import javax.inject.Inject
import javax.inject.Singleton
import xyz.aresius.audiobooks.core.network.ApiProvider
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.AudiobooksApi
import xyz.aresius.audiobooks.core.network.DebridStatus
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.PutDebridKeyRequest
import xyz.aresius.audiobooks.core.network.safeApiCall

/** The caller's own debrid keys. The server never returns a key, so the app never holds one beyond the moment it is sent. */
@Singleton
class DebridRepository(private val apiSource: suspend () -> AudiobooksApi) {
    @Inject
    constructor(provider: ApiProvider) : this({ provider.api() })

    /** One entry per provider the server supports (a provider the server omits is shown as not configured by the UI). */
    suspend fun list(): ApiResult<List<DebridStatus>> = safeApiCall { apiSource().listDebrid().providers }

    /** Validates the key against the provider and stores it (`400 bad_debrid_key` when rejected). */
    suspend fun put(provider: Provider, apiKey: String): ApiResult<DebridStatus> =
        safeApiCall { apiSource().putDebridKey(provider.wire, PutDebridKeyRequest(apiKey.trim())) }

    suspend fun recheck(provider: Provider): ApiResult<DebridStatus> = safeApiCall { apiSource().recheckDebridKey(provider.wire) }

    suspend fun delete(provider: Provider): ApiResult<Unit> = safeApiCall { apiSource().deleteDebridKey(provider.wire) }
}
