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

private val Weights = listOf(400, 500, 600, 700, 800)

/**
 * Fraunces (OFL, variable: opsz, wght, SOFT, WONK) for display and titles: SOFT 50, WONK off, and the optical size tied to the size of the
 * text it sets (one family per size class: a variable font file has one setting per `Font`).
 */
@OptIn(ExperimentalTextApi::class)
private fun fraunces(opticalSize: Float) = FontFamily(
    Weights.map { w ->
        Font(
            R.font.fraunces,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(
                FontVariation.weight(w),
                FontVariation.Setting("opsz", opticalSize),
                FontVariation.Setting("SOFT", 50f),
                FontVariation.Setting("WONK", 0f),
            ),
        )
    },
)

/** Hanken Grotesk (OFL, variable: wght) for the interface and body text. */
@OptIn(ExperimentalTextApi::class)
val HankenGrotesk = FontFamily(
    Weights.map { w ->
        Font(R.font.hanken_grotesk, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

val FrauncesDisplay = fraunces(72f)
val FrauncesHeadline = fraunces(40f)
val FrauncesTitle = fraunces(24f)

private fun serif(family: FontFamily, size: Int, line: Int, weight: FontWeight, tracking: TextUnit) =
    TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking)

private fun sans(size: Int, line: Int, weight: FontWeight, tracking: TextUnit = 0.sp) =
    TextStyle(fontFamily = HankenGrotesk, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking)

/**
 * Editorial scale: big serif headlines with tight tracking, quiet grotesque body, small medium-weight labels with a little air.
 * Titles of books and sections are serif; list metadata, buttons and chips are grotesque.
 */
val AudiobooksTypography = Typography(
    displayLarge = serif(FrauncesDisplay, 48, 52, FontWeight.Bold, (-1.2).sp),
    displayMedium = serif(FrauncesDisplay, 44, 48, FontWeight.Bold, (-1.0).sp),
    displaySmall = serif(FrauncesDisplay, 36, 42, FontWeight.Bold, (-0.6).sp),
    headlineLarge = serif(FrauncesHeadline, 34, 40, FontWeight.Bold, (-0.5).sp),
    headlineMedium = serif(FrauncesHeadline, 28, 34, FontWeight.SemiBold, (-0.3).sp),
    headlineSmall = serif(FrauncesHeadline, 24, 30, FontWeight.SemiBold, (-0.2).sp),
    titleLarge = serif(FrauncesTitle, 22, 28, FontWeight.SemiBold, (-0.1).sp),
    titleMedium = serif(FrauncesTitle, 18, 24, FontWeight.SemiBold, 0.sp),
    titleSmall = sans(15, 20, FontWeight.SemiBold, 0.1.sp),
    bodyLarge = sans(16, 24, FontWeight.Normal, 0.1.sp),
    bodyMedium = sans(14, 20, FontWeight.Normal, 0.15.sp),
    bodySmall = sans(12, 16, FontWeight.Normal, 0.3.sp),
    labelLarge = sans(14, 20, FontWeight.Medium, 0.3.sp),
    labelMedium = sans(12, 16, FontWeight.Medium, 0.5.sp),
    labelSmall = sans(11, 16, FontWeight.Medium, 0.6.sp),
)

/** Time and progress figures keep a fixed width, so a running clock does not jitter. */
val TabularFigures = TextStyle(fontFeatureSettings = "tnum")
