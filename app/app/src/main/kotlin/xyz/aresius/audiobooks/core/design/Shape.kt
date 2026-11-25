package xyz.aresius.audiobooks.core.design

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Large radii, Material 3 Expressive spirit. */
val AudiobooksShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(40.dp),
)

val CoverShape = RoundedCornerShape(24.dp)
val CardShape = RoundedCornerShape(28.dp)
val PillShape = RoundedCornerShape(percent = 50)
val RoundShape = CircleShape

/** The squarish rounded button of the player (skip back/forward). */
val SquircleShape = RoundedCornerShape(percent = 34)

/** The one shadow style of the app: a soft, warm lift under covers. */
fun Modifier.coverShadow(shape: Shape = CoverShape): Modifier = shadow(
    elevation = 14.dp,
    shape = shape,
    clip = false,
    ambientColor = PaperPalette.shadow.copy(alpha = 0.30f),
    spotColor = PaperPalette.shadow.copy(alpha = 0.45f),
)

/** 8 dp grid. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp

    /** Horizontal screen margin. */
    val screen = 20.dp
}
