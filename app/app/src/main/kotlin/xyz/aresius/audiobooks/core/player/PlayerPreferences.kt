package xyz.aresius.audiobooks.core.player

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Settings > Playback. */
interface PlayerPreferences {
    val skipBackSeconds: Flow<Int>
    val skipForwardSeconds: Flow<Int>
    val smartRewind: Flow<Boolean>
    suspend fun setSkipBackSeconds(seconds: Int)
    suspend fun setSkipForwardSeconds(seconds: Int)
    suspend fun setSmartRewind(enabled: Boolean)
}

private val Context.playerDataStore: DataStore<Preferences> by preferencesDataStore("audiobooks_player")

class DataStorePlayerPreferences(private val store: DataStore<Preferences>) : PlayerPreferences {
    override val skipBackSeconds: Flow<Int> = store.data.map { PlaybackRules.validSkip(it[SKIP_BACK] ?: PlaybackRules.DEFAULT_SKIP_S) }
    override val skipForwardSeconds: Flow<Int> = store.data.map { PlaybackRules.validSkip(it[SKIP_FORWARD] ?: PlaybackRules.DEFAULT_SKIP_S) }
    override val smartRewind: Flow<Boolean> = store.data.map { it[SMART_REWIND] ?: true }

    override suspend fun setSkipBackSeconds(seconds: Int) {
        store.edit { it[SKIP_BACK] = PlaybackRules.validSkip(seconds) }
    }

    override suspend fun setSkipForwardSeconds(seconds: Int) {
        store.edit { it[SKIP_FORWARD] = PlaybackRules.validSkip(seconds) }
    }

    override suspend fun setSmartRewind(enabled: Boolean) {
        store.edit { it[SMART_REWIND] = enabled }
    }

    private companion object {
        val SKIP_BACK = intPreferencesKey("skip_back_s")
        val SKIP_FORWARD = intPreferencesKey("skip_forward_s")
        val SMART_REWIND = booleanPreferencesKey("smart_rewind")
    }
}

@Singleton
class DevicePlayerPreferences @Inject constructor(@ApplicationContext context: Context) :
    PlayerPreferences by DataStorePlayerPreferences(context.playerDataStore)

suspend fun PlayerPreferences.skipBackMs(): Long = skipBackSeconds.first() * 1000L

suspend fun PlayerPreferences.skipForwardMs(): Long = skipForwardSeconds.first() * 1000L
