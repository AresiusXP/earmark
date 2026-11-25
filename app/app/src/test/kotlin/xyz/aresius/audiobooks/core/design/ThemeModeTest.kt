package xyz.aresius.audiobooks.core.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeModeTest {
    @Test fun systemFollowsThePhoneAndTheOthersIgnoreIt() {
        for (phoneDark in listOf(false, true)) {
            assertEquals(phoneDark, ThemeMode.System.isDark(phoneDark))
            assertFalse(ThemeMode.Light.isDark(phoneDark))
            assertTrue(ThemeMode.Dark.isDark(phoneDark))
        }
    }

    @Test fun storedValuesRoundTripAndUnknownOnesFallBackToSystem() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromKey(it.key)) }
        assertEquals(ThemeMode.System, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.System, ThemeMode.fromKey("sepia"))
    }

    @Test fun thePlayerIsDarkUnlessItFollowsALightTheme() {
        assertTrue(playerIsDark(appDark = false, followsTheme = false))
        assertTrue(playerIsDark(appDark = true, followsTheme = false))
        assertTrue(playerIsDark(appDark = true, followsTheme = true))
        assertFalse(playerIsDark(appDark = false, followsTheme = true))
    }
}
