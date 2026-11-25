package xyz.aresius.audiobooks.feature.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import xyz.aresius.audiobooks.core.qr.QrCode

/** Draws [text] as a QR code: black modules on white with the 4-module quiet zone scanners need (also in the dark theme). */
@Composable
fun QrImage(text: String, contentDescription: String, modifier: Modifier = Modifier) {
    val qr = remember(text) { runCatching { QrCode.encode(text) }.getOrNull() } ?: return
    Canvas(modifier.semantics { this.contentDescription = contentDescription }) {
        val modules = qr.size + 2 * QUIET_ZONE
        val cell = kotlin.math.floor(size.minDimension / modules)
        val side = cell * modules
        val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
        drawRect(Color.White, origin, Size(side, side))
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr.isDark(x, y)) drawRect(Color.Black, origin + Offset((x + QUIET_ZONE) * cell, (y + QUIET_ZONE) * cell), Size(cell, cell))
        }
    }
}

private const val QUIET_ZONE = 4
