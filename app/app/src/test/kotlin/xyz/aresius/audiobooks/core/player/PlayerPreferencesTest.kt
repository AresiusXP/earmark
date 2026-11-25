package xyz.aresius.audiobooks.core.player

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class PlayerPreferencesTest {
    private fun prefs(file: File, scope: CoroutineScope) =
        DataStorePlayerPreferences(PreferenceDataStoreFactory.create(scope = scope) { file })

    @Test fun `defaults are 30 seconds each way and smart rewind on, and the choices survive a restart`() = runBlocking {
        val file = File.createTempFile("player-prefs", ".preferences_pb").also { it.delete(); it.deleteOnExit() }
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val p = prefs(file, first)
        assertEquals(30, p.skipBackSeconds.first())
        assertEquals(30, p.skipForwardSeconds.first())
        assertEquals(true, p.smartRewind.first())
        assertEquals(30_000L, p.skipBackMs())
        p.setSkipBackSeconds(15)
        p.setSkipForwardSeconds(60)
        p.setSmartRewind(false)
        first.cancel()

        val again = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val q = prefs(file, again)
        assertEquals(15, q.skipBackSeconds.first())
        assertEquals(60_000L, q.skipForwardMs())
        assertEquals(false, q.smartRewind.first())
        again.cancel()
    }

    @Test fun `a length that is not on offer is never stored or served`() = runBlocking {
        val file = File.createTempFile("player-prefs", ".preferences_pb").also { it.delete(); it.deleteOnExit() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val p = prefs(file, scope)
        p.setSkipBackSeconds(7)
        assertEquals(30, p.skipBackSeconds.first())
        p.setSkipForwardSeconds(45)
        assertEquals(45, p.skipForwardSeconds.first())
        scope.cancel()
    }
}
