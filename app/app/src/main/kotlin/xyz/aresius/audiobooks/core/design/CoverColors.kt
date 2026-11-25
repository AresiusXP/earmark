package xyz.aresius.audiobooks.core.design

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Colors taken from a cover for the book detail (and later the player): a background that is the cover's swatch darkened toward the warm ink
 * (or lifted toward the paper in the light theme) and desaturated, text that keeps WCAG AA on it, and an accent that does too.
 * [glow] is the cover's swatch at low alpha, for the soft warm glow behind a cover (dark theme) or a warm shadow (light theme).
 */
data class CoverColors(
    val background: Color,
    val onBackground: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val tonalSurface: Color,
    val glow: Color,
)

object CoverColorDeriver {
    private const val TEXT_RATIO = 4.5
    private const val MAX_DARK_L = 0.13f
    private const val MIN_LIGHT_L = 0.88f

    /**
     * [swatch] is a Palette swatch (dominant or muted); [vibrant] an optional more saturated one for the accent. [ink] and [paper] are the
     * active theme's base colors. Returns colors with AA contrast between background/onBackground, muted/background and accent/background.
     */
    fun derive(swatch: Color, vibrant: Color?, dark: Boolean, ink: Color, paper: Color): CoverColors {
        val quiet = ColorMath.scaleSaturation(swatch, 0.65f)
        var bg = if (dark) ColorMath.mix(quiet, ink, 0.66f) else ColorMath.mix(quiet, paper, 0.80f)
        // A pale cover (dark text on white) must not turn the dark page gray, nor a dark one the light page muddy: keep the tone of the theme.
        bg = ColorMath.toHsl(bg).let { hsl -> ColorMath.fromHsl(hsl.copy(l = if (dark) hsl.l.coerceAtMost(MAX_DARK_L) else hsl.l.coerceAtLeast(MIN_LIGHT_L))) }
        var on = if (ColorMath.contrast(ink, bg) >= ColorMath.contrast(paper, bg)) ink else paper
        // Pull the background toward the side opposite the text until the text is readable (always terminates: ink/paper themselves pass).
        var steps = 0
        while (ColorMath.contrast(on, bg) < TEXT_RATIO && steps < 20) {
            bg = ColorMath.mix(bg, if (on == paper) ink else paper, 0.15f)
            steps++
        }
        val muted = nudge(ColorMath.mix(on, bg, 0.30f), bg, on)
        val accent = accentFor(vibrant ?: swatch, bg, on == paper)
        val onAccent = if (ColorMath.contrast(ink, accent) >= ColorMath.contrast(paper, accent)) ink else paper
        val tonal = ColorMath.mix(bg, on, 0.07f)
        return CoverColors(bg, on, muted, accent, onAccent, tonal, glow = swatch.copy(alpha = 0.34f))
    }

    /**
     * The cover's hue as the accent: saturation kept (at least 0.4, so a gray cover still gets a warm accent), only the lightness moves
     * (lighter on a dark page, darker on a light one) until it reads on [bg] with AA contrast.
     */
    private fun accentFor(source: Color, bg: Color, lighten: Boolean): Color {
        val hsl = ColorMath.toHsl(source)
        var l = hsl.l.coerceIn(0.2f, 0.8f)
        val s = hsl.s.coerceIn(0.4f, 0.9f)
        var out = ColorMath.fromHsl(ColorMath.Hsl(hsl.h, s, l))
        var steps = 0
        while (ColorMath.contrast(out, bg) < TEXT_RATIO && steps < 40) {
            l = (l + if (lighten) 0.02f else -0.02f).coerceIn(0f, 1f)
            out = ColorMath.fromHsl(ColorMath.Hsl(hsl.h, s, l))
            steps++
        }
        // Always terminates in a pass: the extremes (near-white on dark, near-black on light) have AA contrast on these backgrounds.
        return if (ColorMath.contrast(out, bg) >= TEXT_RATIO) out else nudge(out, bg, if (lighten) Color.White else Color.Black)
    }

    /** Moves [c] toward [target] (the text color) until it reads on [bg] with AA contrast. */
    private fun nudge(c: Color, bg: Color, target: Color): Color {
        var out = c
        var steps = 0
        while (ColorMath.contrast(out, bg) < TEXT_RATIO && steps < 24) {
            out = ColorMath.mix(out, target, 0.12f)
            steps++
        }
        return out
    }
}

/** Palette of the cover at [url], or null while loading, without a cover, or on failure (callers fall back to the base scheme). */
@Composable
fun rememberCoverColors(url: String?): State<CoverColors?> {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.let { ColorMath.luminance(it) < 0.3 }
    val extras = MaterialTheme.extras
    val ink = extras.ink
    val paper = extras.paper
    return produceState<CoverColors?>(initialValue = null, url, dark, ink, paper) {
        if (url.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            runCatching {
                val request = ImageRequest.Builder(context).data(url).allowHardware(false).crossfade(false).size(coil3.size.Size(240, 240)).build()
                val bitmap: Bitmap = SingletonImageLoader.get(context).execute(request).image?.toBitmap() ?: return@runCatching null
                val palette = // clearFilters: the default filter drops hues 10-37 degrees (its "skin tone" I-line), which is exactly the warm range of many covers.
                Palette.from(bitmap).maximumColorCount(16).resizeBitmapArea(160 * 160).clearFilters().generate()
                // The most present color, weighted toward the colorful ones: a white title on a brown cover must not win.
                val base = palette.swatches.maxByOrNull { it.population * (0.25f + it.hsl[1]) } ?: palette.dominantSwatch ?: return@runCatching null
                val vibrant = palette.vibrantSwatch ?: palette.lightVibrantSwatch
                CoverColorDeriver.derive(Color(base.rgb), vibrant?.let { Color(it.rgb) }, dark, ink, paper)
            }.getOrNull()
        }
    }
}

/**
 * The base scheme with the cover's colors applied (animated cross-fade); with no cover colors the base scheme unchanged. Children styled with
 * MaterialTheme (text, badges, cards) pick the colors up.
 */
@Composable
fun coverColorScheme(base: ColorScheme, cover: CoverColors?): ColorScheme {
    @Composable fun fade(target: Color): Color {
        val c by animateColorAsState(target, tween(Motion.COVER_COLOR_MS), label = "cover-color")
        return c
    }
    val bg = fade(cover?.background ?: base.background)
    val on = fade(cover?.onBackground ?: base.onSurface)
    val muted = fade(cover?.muted ?: base.onSurfaceVariant)
    val accent = fade(cover?.accent ?: base.primary)
    val onAccent = fade(cover?.onAccent ?: base.onPrimary)
    val tonal = fade(cover?.tonalSurface ?: base.surface)
    return base.copy(
        background = bg, onBackground = on, surface = tonal, onSurface = on, surfaceVariant = tonal, onSurfaceVariant = muted,
        primary = accent, onPrimary = onAccent, surfaceContainer = tonal, surfaceContainerLow = tonal, surfaceContainerHigh = tonal,
        surfaceContainerHighest = tonal, surfaceContainerLowest = bg, outlineVariant = muted.copy(alpha = 0.3f),
    )
}
