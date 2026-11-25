package xyz.aresius.audiobooks.core.design

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

val LocalAudiobooksExtras = compositionLocalOf { PaperPalette.extras(dark = false) }

/** The extras of the active theme: `MaterialTheme.extras.accentText`. */
val MaterialTheme.extras: AudiobooksExtras
    @Composable @ReadOnlyComposable get() = LocalAudiobooksExtras.current

/** Dynamic (wallpaper) color needs Android 12; the Settings switch is only shown from there. */
val DynamicColorSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Own light (paper) and dark (ink) schemes following the system setting. Wallpaper-derived dynamic color is OFF unless the user turns it
 * on in Settings ([dynamicColor]); the extras (accent text, mustard) then derive from the dynamic scheme.
 */
@Composable
fun AudiobooksTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val palette = if (darkTheme) InkPalette else PaperPalette
    val useDynamic = dynamicColor && DynamicColorSupported
    val scheme = remember(darkTheme, useDynamic) {
        if (useDynamic) (if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)) else palette.toColorScheme(darkTheme)
    }
    val extras = remember(darkTheme, useDynamic, scheme) {
        if (useDynamic) {
            palette.extras(darkTheme).copy(accentText = scheme.primary, mustard = scheme.secondaryContainer, onMustard = scheme.onSecondaryContainer)
        } else {
            palette.extras(darkTheme)
        }
    }
    CompositionLocalProvider(LocalAudiobooksExtras provides extras) {
        // The expressive motion scheme is not public in material3 1.4: spring motion lives in Motion.kt instead.
        MaterialTheme(
            colorScheme = scheme,
            shapes = AudiobooksShapes,
            typography = AudiobooksTypography,
            content = content,
        )
    }
}

/** Theme + background fill, for previews. */
@Composable
fun AudiobooksPreviewSurface(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    AudiobooksTheme(darkTheme = darkTheme) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
    }
}
