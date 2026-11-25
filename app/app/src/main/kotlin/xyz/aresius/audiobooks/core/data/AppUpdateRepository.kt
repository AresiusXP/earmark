package xyz.aresius.audiobooks.core.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import xyz.aresius.audiobooks.core.network.ApiProvider
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.AudiobooksApi
import xyz.aresius.audiobooks.core.network.safeApiCall
import xyz.aresius.audiobooks.core.update.AppUpdateInfo

/** `GET /v1/app/update` (a public route): is a newer build bundled with the server? */
interface AppUpdateRepository {
    /** Success(null) = up to date (or nothing bundled, or an offer that fails validation). */
    suspend fun check(installedVersionCode: Long): ApiResult<AppUpdateInfo?>
}

@Singleton
class AppUpdateRepositoryImpl(
    private val apiSource: suspend () -> AudiobooksApi,
) : AppUpdateRepository {
    @Inject
    constructor(provider: ApiProvider, prefs: AppPreferences) : this({ provider.unauthenticated(prefs.serverUrl.first()) })

    override suspend fun check(installedVersionCode: Long): ApiResult<AppUpdateInfo?> =
        when (val r = safeApiCall { apiSource().getAppUpdate() }) {
            is ApiResult.Success -> ApiResult.Success(AppUpdateInfo.from(r.value, installedVersionCode))
            is ApiResult.Failure -> r
        }
}
