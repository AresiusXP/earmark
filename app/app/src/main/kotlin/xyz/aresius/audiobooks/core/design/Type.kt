package xyz.aresius.audiobooks.core.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import xyz.aresius.audiobooks.R

/** Figtree variable font (OFL, see assets/licenses/OFL-Figtree.txt). */
@OptIn(ExperimentalTextApi::class)
val Figtree = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        Font(
            R.font.figtree,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    },
)

private fun style(size: Int, weight: FontWeight, line: Int, spacing: TextUnit = 0.sp) = TextStyle(
    fontFamily = Figtree,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing,
)

/**
 * Spotify-like scale: bold large titles, medium body, small captions.
 * display = hero titles, headline = screen titles, title = section/row titles, body = text, label = buttons/chips.
 */
val AudiobooksTypography = Typography(
    displayLarge = style(40, FontWeight.ExtraBold, 48, (-0.5).sp),
    displayMedium = style(32, FontWeight.ExtraBold, 40, (-0.4).sp),
    displaySmall = style(28, FontWeight.Bold, 36),
    headlineLarge = style(26, FontWeight.Bold, 32),
    headlineMedium = style(22, FontWeight.Bold, 28),
    headlineSmall = style(20, FontWeight.Bold, 26),
    titleLarge = style(20, FontWeight.Bold, 26),
    titleMedium = style(16, FontWeight.SemiBold, 22),
    titleSmall = style(14, FontWeight.SemiBold, 20),
    bodyLarge = style(16, FontWeight.Medium, 22),
    bodyMedium = style(14, FontWeight.Medium, 20),
    bodySmall = style(12, FontWeight.Medium, 16),
    labelLarge = style(14, FontWeight.Bold, 20, 0.1.sp),
    labelMedium = style(12, FontWeight.SemiBold, 16, 0.2.sp),
    labelSmall = style(10, FontWeight.SemiBold, 14, 0.3.sp),
)
