package xyz.aresius.audiobooks.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import xyz.aresius.audiobooks.core.design.ThemeMode
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.MainDispatcherRule
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.awaitState

class AppViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val server = FakeServer()
    private val app = TestApp(server)

    @AfterTest fun tearDown() = server.close()

    @Test fun theAppearanceIsLoadedFromThePreferencesAndFollowsEveryChangeWithoutARestart() = runBlocking<Unit> {
        app.prefs.setThemeMode(ThemeMode.Light)
        val vm = AppViewModel(app.sessions, app.prefs, app.me)
        assertEquals(ThemeMode.Light, vm.appearance.awaitState { it != null }?.themeMode)
        assertEquals(false, vm.appearance.value?.playerFollowsTheme)

        app.prefs.setThemeMode(ThemeMode.Dark)
        app.prefs.setPlayerFollowsTheme(true)
        val a = vm.appearance.awaitState { it?.themeMode == ThemeMode.Dark && it.playerFollowsTheme }
        assertNotNull(a)
        assertEquals(false, a.dynamicColor)
    }
}
