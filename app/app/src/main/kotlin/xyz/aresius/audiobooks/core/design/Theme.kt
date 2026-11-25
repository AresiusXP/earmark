package xyz.aresius.audiobooks.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

object AudiobooksColors {
    val Background = Color(0xFF0E0D0B)
    val Surface = Color(0xFF181612)
    val SurfaceVariant = Color(0xFF242119)
    val OnBackground = Color(0xFFF6F1E7)
    val OnSurfaceVariant = Color(0xFFB9B1A1)
    val Accent = Color(0xFFF2A93B)
    val OnAccent = Color(0xFF1C1300)
    val Error = Color(0xFFFF6B6B)
    val Outline = Color(0xFF3A3629)
    val Positive = Color(0xFF6FCF97)
}

private val AudiobooksDarkColors = darkColorScheme(
    primary = AudiobooksColors.Accent,
    onPrimary = AudiobooksColors.OnAccent,
    primaryContainer = AudiobooksColors.Accent,
    onPrimaryContainer = AudiobooksColors.OnAccent,
    secondary = AudiobooksColors.Accent,
    onSecondary = AudiobooksColors.OnAccent,
    background = AudiobooksColors.Background,
    onBackground = AudiobooksColors.OnBackground,
    surface = AudiobooksColors.Surface,
    onSurface = AudiobooksColors.OnBackground,
    surfaceVariant = AudiobooksColors.SurfaceVariant,
    onSurfaceVariant = AudiobooksColors.OnSurfaceVariant,
    surfaceContainerLowest = AudiobooksColors.Background,
    surfaceContainerLow = AudiobooksColors.Surface,
    surfaceContainer = AudiobooksColors.Surface,
    surfaceContainerHigh = AudiobooksColors.SurfaceVariant,
    surfaceContainerHighest = AudiobooksColors.SurfaceVariant,
    error = AudiobooksColors.Error,
    onError = Color.White,
    outline = AudiobooksColors.Outline,
    outlineVariant = AudiobooksColors.Outline,
)

/** Dark only: the app has no light theme. */
@Composable
fun AudiobooksTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AudiobooksDarkColors,
        typography = AudiobooksTypography,
        shapes = AudiobooksShapes,
    ) {
        content()
    }
}

/** Theme + background fill, for previews. */
@Composable
fun AudiobooksPreviewSurface(content: @Composable () -> Unit) {
    AudiobooksTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
    }
}
