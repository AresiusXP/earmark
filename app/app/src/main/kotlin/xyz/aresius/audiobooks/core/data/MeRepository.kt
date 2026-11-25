package xyz.aresius.audiobooks.core.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.aresius.audiobooks.core.network.ApiProvider
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.core.network.AudiobooksApi
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Device
import xyz.aresius.audiobooks.core.network.LinkCodeResponse
import xyz.aresius.audiobooks.core.network.Me
import xyz.aresius.audiobooks.core.network.Settings
import xyz.aresius.audiobooks.core.network.SettingsPatch
import xyz.aresius.audiobooks.core.network.SourceMode
import xyz.aresius.audiobooks.core.network.UpdateMeRequest
import xyz.aresius.audiobooks.core.network.safeApiCall

/** The signed-in user (`/v1/me`), their settings and devices. [me] is the single in-memory copy every screen observes. */
@Singleton
class MeRepository(
    private val apiSource: suspend () -> AudiobooksApi,
    private val cache: CacheDao,
    private val clock: () -> Long,
) {
    @Inject
    constructor(provider: ApiProvider, cache: CacheDao) : this({ provider.api() }, cache, System::currentTimeMillis)

    private val _me = MutableStateFlow<Me?>(null)
    val me: StateFlow<Me?> = _me.asStateFlow()

    private val lock = Any()
    private var version = 0L

    /** Installs [me] unless [onlyIfVersion] is given and something else was installed since; returns whether it was. */
    private fun install(me: Me, onlyIfVersion: Long? = null): Boolean = synchronized(lock) {
        if (onlyIfVersion != null && onlyIfVersion != version) return false
        version++
        _me.value = me
        true
    }

    private fun versionNow(): Long = synchronized(lock) { version }

    /** After sign-in: the session answer already carries the user. */
    suspend fun set(me: Me) = store(me, install(me))

    private suspend fun store(me: Me, installed: Boolean) {
        if (installed) cache.put(CacheEntryEntity(KEY, AudiobooksJson.encodeToString(Me.serializer(), me), clock()))
    }

    /** Loads the last known user from the cache (cold start, offline). */
    suspend fun restore() {
        if (_me.value != null) return
        val json = cache.get(KEY)?.json ?: return
        _me.value = runCatching { AudiobooksJson.decodeFromString(Me.serializer(), json) }.getOrNull()
    }

    /**
     * Reads the user again. An answer that was overtaken by a write (a settings PATCH, a rename) while it was on its way is older than what
     * [me] holds, so it is dropped instead of undoing the write.
     */
    suspend fun refresh(): ApiResult<Me> {
        val seen = versionNow()
        return safeApiCall { apiSource().getMe() }.also { if (it is ApiResult.Success) store(it.value, install(it.value, seen)) }
    }

    suspend fun update(request: UpdateMeRequest): ApiResult<Me> =
        safeApiCall { apiSource().updateMe(request) }.also { if (it is ApiResult.Success) set(it.value) }

    suspend fun updateSettings(patch: SettingsPatch): ApiResult<Me> = update(UpdateMeRequest(settings = patch))

    suspend fun rename(displayName: String): ApiResult<Me> = update(UpdateMeRequest(displayName = displayName.trim()))

    /** BitTorrent switch plus the source mode that stays coherent with it (see [P2pSettings]). */
    suspend fun setP2p(enabled: Boolean): ApiResult<Me> {
        val current = _me.value?.settings ?: Settings()
        return updateSettings(P2pSettings.patch(enabled, current.sourceMode))
    }

    suspend fun devices(): ApiResult<List<Device>> = safeApiCall { apiSource().listDevices().devices }

    suspend fun revokeDevice(id: String): ApiResult<Unit> = safeApiCall { apiSource().revokeDevice(id) }

    suspend fun createLinkCode(): ApiResult<LinkCodeResponse> = safeApiCall { apiSource().createLinkCode() }

    suspend fun clear() {
        synchronized(lock) {
            version++
            _me.value = null
        }
    }

    private companion object {
        const val KEY = "me"
    }
}

/** The two settings that describe BitTorrent: `p2p_enabled` (user's explicit choice) and `source_mode`. Pure so the rules are testable. */
object P2pSettings {
    /**
     * Turning BitTorrent ON also leaves "debrid" (= never BitTorrent) for "auto". Turning it OFF leaves "p2p" (= BitTorrent even with a key),
     * which would contradict the switch, for "auto". Every other mode stays as the user chose it.
     */
    fun patch(enabled: Boolean, currentMode: String): SettingsPatch {
        val mode = when {
            enabled && currentMode == SourceMode.DEBRID -> SourceMode.AUTO
            !enabled && currentMode == SourceMode.P2P -> SourceMode.AUTO
            else -> currentMode
        }
        return SettingsPatch(p2pEnabled = enabled, sourceMode = mode)
    }
}
