package xyz.aresius.audiobooks.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import xyz.aresius.audiobooks.core.design.ThemeMode

class AppPreferencesTest {
    private fun tempFile() = File.createTempFile("app-prefs", ".preferences_pb").also { it.delete(); it.deleteOnExit() }

    @Test fun `theme is System and the player stays dark by default, and both choices survive a restart`() = runBlocking {
        val file = tempFile()
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val p = DataStoreAppPreferences(PreferenceDataStoreFactory.create(scope = first) { file })
        assertEquals(ThemeMode.System, p.themeMode.first())
        assertFalse(p.playerFollowsTheme.first())
        p.setThemeMode(ThemeMode.Light)
        p.setPlayerFollowsTheme(true)
        first.cancel()

        val again = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val q = DataStoreAppPreferences(PreferenceDataStoreFactory.create(scope = again) { file })
        assertEquals(ThemeMode.Light, q.themeMode.first())
        assertEquals(true, q.playerFollowsTheme.first())
        q.setThemeMode(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, q.themeMode.first())
        again.cancel()
    }

    @Test fun `an unknown stored theme reads as System`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { tempFile() }
        store.edit { it[stringPreferencesKey("theme_mode")] = "sepia" }
        assertEquals(ThemeMode.System, DataStoreAppPreferences(store).themeMode.first())
        scope.cancel()
    }
}
