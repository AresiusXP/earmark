package xyz.aresius.audiobooks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import xyz.aresius.audiobooks.app.AppViewModel
import xyz.aresius.audiobooks.app.AudiobooksAppRoot
import xyz.aresius.audiobooks.core.design.AudiobooksTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // bars follow the system light/dark setting, like the theme
        setContent {
            val viewModel: AppViewModel = hiltViewModel()
            val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
            AudiobooksTheme(dynamicColor = dynamicColor) { AudiobooksAppRoot(viewModel) }
        }
    }
}
