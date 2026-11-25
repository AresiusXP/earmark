package xyz.aresius.audiobooks.core.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The "reading room" palette (app/DESIGN.md): warm paper, ink, one hot accent (persimmon). No blue, no green, no purple gradient.
 * Colors are plain values so the contrast test can check every text/background pair of both schemes.
 */
class AudiobooksPalette(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    /** Secondary ("muted") text. */
    val muted: Color,
    val outline: Color,
    val outlineVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    /** The accent as small text on [background] / [surface]: persimmon itself is only 3.7:1 on paper, so text uses a deeper one. */
    val accentText: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    /** Cover shadow tint. */
    val shadow: Color,
) {
    fun toColorScheme(dark: Boolean): ColorScheme {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            inversePrimary = primaryContainer,
            secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
            error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer,
            background = background, onBackground = onSurface,
            surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = muted,
            surfaceTint = primary,
            surfaceContainerLowest = background, surfaceContainerLow = surface, surfaceContainer = surface,
            surfaceContainerHigh = surfaceVariant, surfaceContainerHighest = surfaceVariant, surfaceDim = background, surfaceBright = surface,
            inverseSurface = onSurface, inverseOnSurface = background,
            outline = outline, outlineVariant = outlineVariant, scrim = Color.Black,
        )
    }
}

val PaperPalette = AudiobooksPalette(
    background = Color(0xFFF6F0E6),
    surface = Color(0xFFFBF7EF),
    surfaceVariant = Color(0xFFEADFCF),
    onSurface = Color(0xFF1E1A17),
    muted = Color(0xFF6B6258),
    outline = Color(0xFF8C8173),
    outlineVariant = Color(0xFFD9CCB8),
    primary = Color(0xFFD9482B),
    onPrimary = Color(0xFFF6F0E6),
    accentText = Color(0xFFB1361C),
    primaryContainer = Color(0xFFF3D3C5),
    onPrimaryContainer = Color(0xFF4A1508),
    secondary = Color(0xFFD9A441),
    onSecondary = Color(0xFF1E1A17),
    secondaryContainer = Color(0xFFF0DDB0),
    onSecondaryContainer = Color(0xFF3B2A05),
    tertiary = Color(0xFF5B2A4B),
    onTertiary = Color(0xFFF6F0E6),
    tertiaryContainer = Color(0xFFE9D4E0),
    onTertiaryContainer = Color(0xFF3A1230),
    error = Color(0xFF9E1B32),
    onError = Color(0xFFF6F0E6),
    errorContainer = Color(0xFFF2D0D5),
    onErrorContainer = Color(0xFF5A0C1C),
    shadow = Color(0xFF3A2A1C),
)

val InkPalette = AudiobooksPalette(
    background = Color(0xFF14110F),
    surface = Color(0xFF1C1815),
    surfaceVariant = Color(0xFF2A2420),
    onSurface = Color(0xFFF1EADF),
    muted = Color(0xFFB8AD9E),
    outline = Color(0xFF7D7366),
    outlineVariant = Color(0xFF3B332C),
    primary = Color(0xFFFF7A55),
    onPrimary = Color(0xFF2B0E05),
    accentText = Color(0xFFFF7A55),
    primaryContainer = Color(0xFF5A2314),
    onPrimaryContainer = Color(0xFFFFD9CC),
    secondary = Color(0xFFE8B558),
    onSecondary = Color(0xFF241800),
    secondaryContainer = Color(0xFF4A3710),
    onSecondaryContainer = Color(0xFFF5DFA8),
    tertiary = Color(0xFFD9A3C8),
    onTertiary = Color(0xFF2E0F25),
    tertiaryContainer = Color(0xFF4A2040),
    onTertiaryContainer = Color(0xFFE8C9DF),
    error = Color(0xFFFF8D9B),
    onError = Color(0xFF4A0613),
    errorContainer = Color(0xFF6B1426),
    onErrorContainer = Color(0xFFFFD9DE),
    shadow = Color.Black,
)

/** Tokens that have no slot in a Material [ColorScheme]. */
data class AudiobooksExtras(
    /** Persimmon as small text (AA on the background and surfaces). */
    val accentText: Color,
    /** "Cached" and other highlights: mustard with ink text. */
    val mustard: Color,
    val onMustard: Color,
    /** Base ink and paper of the active scheme (cover-derived colors blend toward the ink). */
    val ink: Color,
    val paper: Color,
    val coverShadow: Color,
)

fun AudiobooksPalette.extras(dark: Boolean) = AudiobooksExtras(
    accentText = accentText,
    mustard = secondary,
    onMustard = onSecondary,
    ink = if (dark) background else onSurface,
    paper = if (dark) onSurface else background,
    coverShadow = shadow,
)
