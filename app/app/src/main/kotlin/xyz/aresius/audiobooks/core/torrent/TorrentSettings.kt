package xyz.aresius.audiobooks.core.torrent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.core.data.MeRepository

/**
 * Everything the safety policy and the session limits depend on. [p2pEnabled] is the user's explicit opt-in (the server's
 * `settings.p2p_enabled`, mirrored locally); the engine refuses to run while it is false. The rest lives in DataStore.
 */
data class TorrentSettings(
    val p2pEnabled: Boolean = false,
    /** Default ON: every torrent is paused (and the session with it) while no VPN carries the app's traffic. */
    val requireVpn: Boolean = true,
    /** Default OFF: Wi-Fi (or any unmetered network) only. */
    val allowMobileData: Boolean = false,
    /** Default OFF: stop uploading when the download is done. ON: seed to ratio 1.0, only on Wi-Fi while charging. */
    val seedToRatio: Boolean = false,
    val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    val maxActiveTorrents: Int = DEFAULT_MAX_ACTIVE,
    /** KiB/s, 0 = unlimited. */
    val downloadLimitKib: Int = 0,
    val uploadLimitKib: Int = 0,
) {
    companion object {
        const val DEFAULT_MAX_CONNECTIONS = 100
        const val DEFAULT_MAX_ACTIVE = 2
    }
}

interface TorrentSettingsSource {
    /** The effective settings, including the opt-in. */
    val settings: Flow<TorrentSettings>
}

interface TorrentSettingsStore : TorrentSettingsSource {
    suspend fun update(transform: (TorrentSettings) -> TorrentSettings)
}

private val Context.torrentDataStore: DataStore<Preferences> by preferencesDataStore("audiobooks_torrent")

class DataStoreTorrentSettings(
    private val dataStore: DataStore<Preferences>,
    /** The server's `p2p_enabled` of the signed-in user, null while unknown (cold start before the cache is loaded). */
    private val serverOptIn: Flow<Boolean?>,
    scope: CoroutineScope,
) : TorrentSettingsStore {
    init {
        // The opt-in is mirrored locally so a resumed download after a process restart sees it before the user cache is loaded.
        scope.launch {
            serverOptIn.collect { value -> if (value != null) dataStore.edit { it[P2P_MIRROR] = value } }
        }
    }

    override val settings: Flow<TorrentSettings> = combine(dataStore.data, serverOptIn) { prefs, server ->
        TorrentSettings(
            p2pEnabled = server ?: prefs[P2P_MIRROR] ?: false,
            requireVpn = prefs[REQUIRE_VPN] ?: true,
            allowMobileData = prefs[ALLOW_MOBILE] ?: false,
            seedToRatio = prefs[SEED_TO_RATIO] ?: false,
            maxConnections = (prefs[MAX_CONNECTIONS] ?: TorrentSettings.DEFAULT_MAX_CONNECTIONS).coerceIn(10, 500),
            maxActiveTorrents = (prefs[MAX_ACTIVE] ?: TorrentSettings.DEFAULT_MAX_ACTIVE).coerceIn(1, 5),
            downloadLimitKib = (prefs[DOWN_KIB] ?: 0).coerceAtLeast(0),
            uploadLimitKib = (prefs[UP_KIB] ?: 0).coerceAtLeast(0),
        )
    }.distinctUntilChanged()

    override suspend fun update(transform: (TorrentSettings) -> TorrentSettings) {
        val current = settings.first()
        val next = transform(current)
        dataStore.edit {
            it[REQUIRE_VPN] = next.requireVpn
            it[ALLOW_MOBILE] = next.allowMobileData
            it[SEED_TO_RATIO] = next.seedToRatio
            it[MAX_CONNECTIONS] = next.maxConnections
            it[MAX_ACTIVE] = next.maxActiveTorrents
            it[DOWN_KIB] = next.downloadLimitKib
            it[UP_KIB] = next.uploadLimitKib
            if (next.p2pEnabled != current.p2pEnabled) it[P2P_MIRROR] = next.p2pEnabled
        }
    }

    private companion object {
        val P2P_MIRROR = booleanPreferencesKey("p2p_enabled")
        val REQUIRE_VPN = booleanPreferencesKey("require_vpn")
        val ALLOW_MOBILE = booleanPreferencesKey("allow_mobile_data")
        val SEED_TO_RATIO = booleanPreferencesKey("seed_to_ratio")
        val MAX_CONNECTIONS = intPreferencesKey("max_connections")
        val MAX_ACTIVE = intPreferencesKey("max_active_torrents")
        val DOWN_KIB = intPreferencesKey("download_limit_kib")
        val UP_KIB = intPreferencesKey("upload_limit_kib")
    }
}

@Singleton
class DeviceTorrentSettings @Inject constructor(@ApplicationContext context: Context, me: MeRepository) :
    TorrentSettingsStore by DataStoreTorrentSettings(
        context.torrentDataStore,
        me.me.map { it?.settings?.p2pEnabled },
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
