package xyz.aresius.audiobooks.core.download

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

const val GIB = 1024L * 1024 * 1024

/** [storageLimitBytes] 0 = unlimited. [autoDeleteAfterDays] 0 = off. */
data class DownloadPrefs(
    val allowMobileData: Boolean = false,
    val storageLimitBytes: Long = DEFAULT_LIMIT_BYTES,
    val autoDownloadWhileListening: Boolean = false,
    val autoDeleteAfterDays: Int = 0,
) {
    companion object {
        const val DEFAULT_LIMIT_BYTES = 5 * GIB
        val LIMIT_CHOICES = listOf(1 * GIB, 2 * GIB, 5 * GIB, 10 * GIB, 20 * GIB, 0L)
        val AUTO_DELETE_CHOICES = listOf(0, 1, 7, 14, 30)
    }
}

interface DownloadSettings {
    val prefs: Flow<DownloadPrefs>
    suspend fun current(): DownloadPrefs = prefs.first()
    suspend fun setAllowMobileData(value: Boolean)
    suspend fun setStorageLimit(bytes: Long)
    suspend fun setAutoDownloadWhileListening(value: Boolean)
    suspend fun setAutoDeleteAfterDays(days: Int)
}

private val Context.downloadsDataStore: DataStore<Preferences> by preferencesDataStore("audiobooks_downloads")

class DataStoreDownloadSettings(private val dataStore: DataStore<Preferences>) : DownloadSettings {
    override val prefs: Flow<DownloadPrefs> = dataStore.data.map {
        DownloadPrefs(
            allowMobileData = it[MOBILE] ?: false,
            storageLimitBytes = (it[LIMIT] ?: DownloadPrefs.DEFAULT_LIMIT_BYTES).coerceAtLeast(0),
            autoDownloadWhileListening = it[AUTO_DOWNLOAD] ?: false,
            autoDeleteAfterDays = (it[AUTO_DELETE] ?: 0).coerceAtLeast(0),
        )
    }.distinctUntilChanged()

    override suspend fun setAllowMobileData(value: Boolean) {
        dataStore.edit { it[MOBILE] = value }
    }

    override suspend fun setStorageLimit(bytes: Long) {
        dataStore.edit { it[LIMIT] = bytes.coerceAtLeast(0) }
    }

    override suspend fun setAutoDownloadWhileListening(value: Boolean) {
        dataStore.edit { it[AUTO_DOWNLOAD] = value }
    }

    override suspend fun setAutoDeleteAfterDays(days: Int) {
        dataStore.edit { it[AUTO_DELETE] = days.coerceAtLeast(0) }
    }

    private companion object {
        val MOBILE = booleanPreferencesKey("allow_mobile_data")
        val LIMIT = longPreferencesKey("storage_limit_bytes")
        val AUTO_DOWNLOAD = booleanPreferencesKey("auto_download_while_listening")
        val AUTO_DELETE = intPreferencesKey("auto_delete_after_days")
    }
}

@Singleton
class DeviceDownloadSettings @Inject constructor(@ApplicationContext context: Context) :
    DownloadSettings by DataStoreDownloadSettings(context.downloadsDataStore)
