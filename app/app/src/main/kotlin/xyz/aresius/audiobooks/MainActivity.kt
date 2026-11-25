package xyz.aresius.audiobooks

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import xyz.aresius.audiobooks.app.AppViewModel
import xyz.aresius.audiobooks.app.AudiobooksAppRoot
import xyz.aresius.audiobooks.core.design.AudiobooksTheme
import xyz.aresius.audiobooks.core.design.InkPalette
import xyz.aresius.audiobooks.core.design.PaperPalette

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The splash is the brand color in both themes and stays up until the saved theme choice is read, so the wrong theme never flashes.
        installSplashScreen().setKeepOnScreenCondition { appViewModel.appearance.value == null }
        super.onCreate(savedInstanceState)
        setContent {
            val appearance by appViewModel.appearance.collectAsStateWithLifecycle()
            appearance?.let { a ->
                val dark = a.themeMode.isDark(isSystemInDarkTheme())
                DisposableEffect(dark) {
                    applySystemBars(dark)
                    onDispose { }
                }
                AudiobooksTheme(darkTheme = dark, dynamicColor = a.dynamicColor, playerFollowsTheme = a.playerFollowsTheme) { AudiobooksAppRoot(appViewModel) }
            }
        }
    }

    /** Bar icons and window background follow the app's own theme (the in-app choice), not the phone's setting. */
    private fun applySystemBars(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(Color.argb(0xe6, 0xff, 0xff, 0xff), Color.argb(0x80, 0x1b, 0x1b, 0x1b)) { dark },
        )
        val background = (if (dark) InkPalette else PaperPalette).background
        window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
    }
}
