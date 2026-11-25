package xyz.aresius.audiobooks.core.design

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Pure color helpers (WCAG contrast, blending, HSL), usable in JVM tests. */
object ColorMath {
    private fun channel(c: Float): Double = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    /** WCAG relative luminance (alpha ignored). */
    fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    /** WCAG contrast ratio, 1..21. */
    fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun mix(from: Color, to: Color, amount: Float): Color {
        val t = amount.coerceIn(0f, 1f)
        return Color(from.red + (to.red - from.red) * t, from.green + (to.green - from.green) * t, from.blue + (to.blue - from.blue) * t, 1f)
    }

    /** Opaque [fg] drawn at [alpha] over [bg]. */
    fun over(fg: Color, bg: Color, alpha: Float): Color = mix(bg, fg, alpha)

    data class Hsl(val h: Float, val s: Float, val l: Float)

    fun toHsl(c: Color): Hsl {
        val mx = max(c.red, max(c.green, c.blue))
        val mn = min(c.red, min(c.green, c.blue))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d == 0f) return Hsl(0f, 0f, l)
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            c.red -> ((c.green - c.blue) / d + (if (c.green < c.blue) 6f else 0f))
            c.green -> (c.blue - c.red) / d + 2f
            else -> (c.red - c.green) / d + 4f
        } * 60f
        return Hsl(h, s, l)
    }

    fun fromHsl(hsl: Hsl): Color {
        val (h, s, l) = hsl
        if (s == 0f) return Color(l, l, l, 1f)
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Float): Float {
            var t = t0
            if (t < 0) t += 1f
            if (t > 1) t -= 1f
            return when {
                t < 1f / 6 -> p + (q - p) * 6 * t
                t < 1f / 2 -> q
                t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
                else -> p
            }
        }
        val hk = h / 360f
        return Color(hue(hk + 1f / 3), hue(hk), hue(hk - 1f / 3), 1f)
    }

    fun scaleSaturation(c: Color, factor: Float): Color = toHsl(c).let { fromHsl(it.copy(s = (it.s * factor).coerceIn(0f, 1f))) }
}
