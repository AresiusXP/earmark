package xyz.aresius.audiobooks.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.aresius.audiobooks.core.design.ThemeMode

/** Small non-secret app state: which server (empty until the user has entered or saved one; the app ships with no default), whether onboarding finished, update dismissal. Tokens live in the SessionStore. */
interface AppPreferences {
    val serverUrl: Flow<String>
    suspend fun setServerUrl(url: String)

    /** False until the user went through (or skipped) the Debrid and BitTorrent steps after signing in. Reset on sign-out. */
    val onboardingDone: Flow<Boolean>
    suspend fun setOnboardingDone(done: Boolean)

    val dismissedUpdateVersionCode: Flow<Long>
    suspend fun setDismissedUpdateVersionCode(code: Long)

    /** Opt-in wallpaper (dynamic) colors, Android 12+. OFF by default: the app has its own palette. */
    val dynamicColor: Flow<Boolean>
    suspend fun setDynamicColor(enabled: Boolean)

    /** Light (paper), dark (ink) or follow the phone (default). Per device; survives sign-out. */
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

    /** OFF (default): the full-screen player is always dark. ON: it uses the light palette when the app theme is light. */
    val playerFollowsTheme: Flow<Boolean>
    suspend fun setPlayerFollowsTheme(enabled: Boolean)
}

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore("audiobooks_app")

class DataStoreAppPreferences(private val dataStore: DataStore<Preferences>) : AppPreferences {
    override val serverUrl: Flow<String> = dataStore.data.map { it[SERVER_URL].orEmpty() }
    override suspend fun setServerUrl(url: String) {
        dataStore.edit { it[SERVER_URL] = url }
    }

    override val onboardingDone: Flow<Boolean> = dataStore.data.map { it[ONBOARDING_DONE] ?: false }
    override suspend fun setOnboardingDone(done: Boolean) {
        dataStore.edit { it[ONBOARDING_DONE] = done }
    }

    override val dismissedUpdateVersionCode: Flow<Long> = dataStore.data.map { it[DISMISSED_UPDATE] ?: 0L }
    override suspend fun setDismissedUpdateVersionCode(code: Long) {
        dataStore.edit { it[DISMISSED_UPDATE] = code }
    }

    override val dynamicColor: Flow<Boolean> = dataStore.data.map { it[DYNAMIC_COLOR] ?: false }
    override suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    override val themeMode: Flow<ThemeMode> = dataStore.data.map { ThemeMode.fromKey(it[THEME_MODE]) }
    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.key }
    }

    override val playerFollowsTheme: Flow<Boolean> = dataStore.data.map { it[PLAYER_FOLLOWS_THEME] ?: false }
    override suspend fun setPlayerFollowsTheme(enabled: Boolean) {
        dataStore.edit { it[PLAYER_FOLLOWS_THEME] = enabled }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PLAYER_FOLLOWS_THEME = booleanPreferencesKey("player_follows_theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SERVER_URL = stringPreferencesKey("server_url")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val DISMISSED_UPDATE = longPreferencesKey("dismissed_update_version_code")
    }
}

@Singleton
class DeviceAppPreferences @Inject constructor(@ApplicationContext context: Context) :
    AppPreferences by DataStoreAppPreferences(context.appDataStore)

suspend fun AppPreferences.currentServerUrl(): String = serverUrl.first()
