package xyz.aresius.audiobooks.core.design

import androidx.compose.ui.graphics.Color
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WCAG AA for every text/background pair of both palettes: 4.5:1 for normal text, 3:1 for large text (>= 24 sp, or >= 18.7 sp bold: the
 * button labels are 20 sp bold) and for graphical objects (icons, the wave, the play button). Persimmon on paper is only 3.7:1, so
 * small accent text uses `accentText`, and filled persimmon is only ever paired with large labels or icons.
 */
class ContrastTest {
    private data class Pair(val name: String, val fg: Color, val bg: Color, val min: Double)

    private fun pairs(p: AudiobooksPalette, label: String): List<Pair> {
        val normal = 4.5
        val large = 3.0
        return listOf(
            Pair("$label onSurface/background", p.onSurface, p.background, normal),
            Pair("$label onSurface/surface", p.onSurface, p.surface, normal),
            Pair("$label onSurface/surfaceVariant", p.onSurface, p.surfaceVariant, normal),
            Pair("$label muted/background", p.muted, p.background, normal),
            Pair("$label muted/surface", p.muted, p.surface, normal),
            Pair("$label muted/surfaceVariant", p.muted, p.surfaceVariant, normal),
            Pair("$label accentText/background", p.accentText, p.background, normal),
            Pair("$label accentText/surface", p.accentText, p.surface, normal),
            Pair("$label accentText/surfaceVariant", p.accentText, p.surfaceVariant, normal),
            Pair("$label onPrimary/primary (large labels)", p.onPrimary, p.primary, large),
            Pair("$label primary/background (graphics: wave, play button)", p.primary, p.background, large),
            Pair("$label onPrimaryContainer/primaryContainer", p.onPrimaryContainer, p.primaryContainer, normal),
            Pair("$label onSurface/primaryContainer (nav label in the pill)", p.onSurface, p.primaryContainer, normal),
            Pair("$label onSecondary/secondary (mustard badge)", p.onSecondary, p.secondary, normal),
            Pair("$label onSecondaryContainer/secondaryContainer", p.onSecondaryContainer, p.secondaryContainer, normal),
            Pair("$label tertiary/background", p.tertiary, p.background, normal),
            Pair("$label onTertiary/tertiary", p.onTertiary, p.tertiary, normal),
            Pair("$label onTertiaryContainer/tertiaryContainer", p.onTertiaryContainer, p.tertiaryContainer, normal),
            Pair("$label error/background", p.error, p.background, normal),
            Pair("$label error/surface", p.error, p.surface, normal),
            Pair("$label onError/error", p.onError, p.error, normal),
            Pair("$label onErrorContainer/errorContainer", p.onErrorContainer, p.errorContainer, normal),
            Pair("$label outline/background (borders of controls)", p.outline, p.background, large),
        )
    }

    @Test fun lightAndDarkPalettesPassWcagAa() {
        val failures = (pairs(PaperPalette, "light") + pairs(InkPalette, "dark")).filter { ColorMath.contrast(it.fg, it.bg) < it.min }
            .map { "${it.name}: ${"%.2f".format(ColorMath.contrast(it.fg, it.bg))} < ${it.min}" }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test fun theSpecifiedBaseColorsAreUnchanged() {
        assertEquals(Color(0xFFF6F0E6), PaperPalette.background)
        assertEquals(Color(0xFFFBF7EF), PaperPalette.surface)
        assertEquals(Color(0xFFEADFCF), PaperPalette.surfaceVariant)
        assertEquals(Color(0xFF1E1A17), PaperPalette.onSurface)
        assertEquals(Color(0xFF6B6258), PaperPalette.muted)
        assertEquals(Color(0xFFD9482B), PaperPalette.primary)
        assertEquals(Color(0xFFD9A441), PaperPalette.secondary)
        assertEquals(Color(0xFF5B2A4B), PaperPalette.tertiary)
        assertEquals(Color(0xFF14110F), InkPalette.background)
        assertEquals(Color(0xFF1C1815), InkPalette.surface)
        assertEquals(Color(0xFF2A2420), InkPalette.surfaceVariant)
        assertEquals(Color(0xFFF1EADF), InkPalette.onSurface)
        assertEquals(Color(0xFFFF7A55), InkPalette.primary)
    }

    @Test fun noBlueGreenOrPurpleInTheBaseColors() {
        // Warm only: hue between ~330 (rose/plum) and ~60 (mustard) for every chromatic color; plum (tertiary) is the one deliberate exception
        // at ~320 and is a dark red-violet, never a bright purple.
        val colors = listOf(PaperPalette, InkPalette).flatMap {
            listOf(it.background, it.surface, it.surfaceVariant, it.onSurface, it.muted, it.primary, it.accentText, it.secondary, it.error, it.primaryContainer)
        }
        colors.forEach { c ->
            val hsl = ColorMath.toHsl(c)
            if (hsl.s > 0.12f) assertTrue(hsl.h <= 62f || hsl.h >= 340f, "cold hue ${hsl.h} in $c")
        }
        listOf(PaperPalette.tertiary, InkPalette.tertiary).forEach { c ->
            val hsl = ColorMath.toHsl(c)
            assertTrue(hsl.h in 300f..345f, "plum hue ${hsl.h}")
            if (c == PaperPalette.tertiary) assertTrue(hsl.l < 0.30f, "plum stays dark on paper")
        }
    }

    @Test fun errorStaysDistinctFromTheAccent() {
        // Always shown with an icon and words as well, but the colors must differ clearly in lightness or hue too.
        listOf(PaperPalette, InkPalette).forEach { p ->
            val e = ColorMath.toHsl(p.error)
            val a = ColorMath.toHsl(p.primary)
            val distinct = kotlin.math.abs(e.h - a.h) >= 12f || kotlin.math.abs(e.l - a.l) >= 0.12f
            assertTrue(distinct, "error ${p.error} too close to accent ${p.primary}")
        }
    }

    @Test fun theColorSchemesCarryThePalette() {
        val light = PaperPalette.toColorScheme(dark = false)
        assertEquals(PaperPalette.background, light.background)
        assertEquals(PaperPalette.muted, light.onSurfaceVariant)
        assertEquals(PaperPalette.primary, light.primary)
        val dark = InkPalette.toColorScheme(dark = true)
        assertEquals(InkPalette.onSurface, dark.onBackground)
        assertEquals(InkPalette.primary, dark.primary)
    }

    @Test fun coverDerivedColorsAlwaysKeepAaContrast() {
        val random = Random(42)
        repeat(300) {
            val swatch = Color(random.nextFloat(), random.nextFloat(), random.nextFloat(), 1f)
            val vibrant = if (random.nextBoolean()) Color(random.nextFloat(), random.nextFloat(), random.nextFloat(), 1f) else null
            for ((dark, palette) in listOf(false to PaperPalette, true to InkPalette)) {
                val ink = if (dark) palette.background else palette.onSurface
                val paper = if (dark) palette.onSurface else palette.background
                val c = CoverColorDeriver.derive(swatch, vibrant, dark, ink, paper)
                assertTrue(ColorMath.contrast(c.onBackground, c.background) >= 4.5, "text on $swatch dark=$dark")
                assertTrue(ColorMath.contrast(c.muted, c.background) >= 4.5, "muted on $swatch dark=$dark")
                assertTrue(ColorMath.contrast(c.accent, c.background) >= 4.5, "accent on $swatch dark=$dark")
                assertTrue(ColorMath.contrast(c.onBackground, c.tonalSurface) >= 4.5, "text on tonal surface $swatch dark=$dark")
                assertTrue(ColorMath.contrast(c.onAccent, c.accent) >= 4.5, "label on accent $swatch dark=$dark")
            }
        }
    }

    /** The light player (Settings > "Player follows the theme"): cover-derived paper tones with ink text, for any cover. */
    @Test fun theLightPlayerSurfacesKeepAaForAnyCover() {
        val random = Random(7)
        val edge = listOf(Color.Black, Color.White, Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFF0000FF), Color(0xFFFFFF00), Color(0xFF808080), Color(0xFF1E1A17), Color(0xFFF6F0E6))
        val covers = edge + List(500) { Color(random.nextFloat(), random.nextFloat(), random.nextFloat(), 1f) }
        val ink = PaperPalette.onSurface
        val paper = PaperPalette.background
        covers.forEach { swatch ->
            listOf(null, Color(random.nextFloat(), random.nextFloat(), random.nextFloat(), 1f)).forEach { vibrant ->
                val c = CoverColorDeriver.derive(swatch, vibrant, dark = false, ink = ink, paper = paper)
                // the player's surfaces: page, tonal panels (sheets, pills), the muted lines, the accent (wave, play button, links)
                val pairs = listOf(
                    Triple("text/background", c.onBackground, c.background) to 4.5,
                    Triple("text/tonal", c.onBackground, c.tonalSurface) to 4.5,
                    Triple("muted/background", c.muted, c.background) to 4.5,
                    Triple("muted/tonal", c.muted, c.tonalSurface) to 4.5,
                    Triple("accent/background", c.accent, c.background) to 4.5,
                    Triple("accent/tonal", c.accent, c.tonalSurface) to 3.0,
                    Triple("onAccent/accent", c.onAccent, c.accent) to 4.5,
                )
                pairs.forEach { (p, min) ->
                    val ratio = ColorMath.contrast(p.second, p.third)
                    assertTrue(ratio >= min, "light player ${p.first} ${"%.2f".format(ratio)} < $min on $swatch / $vibrant")
                }
                assertTrue(ColorMath.luminance(c.background) > 0.6, "light player stays paper-light on $swatch")
                assertTrue(ColorMath.luminance(c.onBackground) < 0.1, "light player text stays ink-dark on $swatch")
            }
        }
    }

    @Test fun theLightDerivationLightensAndDesaturatesTowardPaperInsteadOfDarkeningTowardInk() {
        val saturated = Color(0xFF8A1C1C)
        val light = CoverColorDeriver.derive(saturated, null, dark = false, ink = PaperPalette.onSurface, paper = PaperPalette.background)
        val dark = CoverColorDeriver.derive(saturated, null, dark = true, ink = InkPalette.background, paper = InkPalette.onSurface)
        assertTrue(ColorMath.luminance(light.background) > ColorMath.luminance(saturated), "lighter than the cover")
        assertTrue(ColorMath.luminance(dark.background) < ColorMath.luminance(saturated), "darker than the cover")
        assertTrue(ColorMath.toHsl(light.background).s < ColorMath.toHsl(saturated).s, "desaturated")
        assertTrue(ColorMath.contrast(light.background, PaperPalette.background) < 1.35, "close to the paper itself")
        assertTrue(light.glow.alpha < 0.4f, "the glow stays subtle")
    }

    @Test fun coverBackgroundsStayCloseToTheBaseSchemeTone() {
        val vividRed = Color(0xFFFF0000)
        val dark = CoverColorDeriver.derive(vividRed, null, true, InkPalette.background, InkPalette.onSurface)
        assertTrue(ColorMath.luminance(dark.background) < 0.08, "dark cover backgrounds stay ink-dark")
        val light = CoverColorDeriver.derive(vividRed, null, false, PaperPalette.onSurface, PaperPalette.background)
        assertTrue(ColorMath.luminance(light.background) > 0.6, "light cover backgrounds stay paper-light")
        assertTrue(dark.glow.alpha < 0.4f, "the glow is subtle")
    }

    @Test fun colorMathBasics() {
        assertEquals(21.0, ColorMath.contrast(Color.Black, Color.White), 0.01)
        assertEquals(1.0, ColorMath.contrast(Color.White, Color.White), 0.001)
        val mid = ColorMath.mix(Color.Black, Color.White, 0.5f)
        assertEquals(0.5f, mid.red, 0.01f)
        val hsl = ColorMath.toHsl(Color(0xFFD9482B))
        val back = ColorMath.fromHsl(hsl)
        assertEquals(Color(0xFFD9482B).red, back.red, 0.01f)
        assertEquals(0f, ColorMath.toHsl(ColorMath.scaleSaturation(Color(0xFFD9482B), 0f)).s, 0.01f)
    }
}
