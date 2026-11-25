package xyz.aresius.audiobooks.core.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.sin

/**
 * The signature element: a progress / seek bar whose played part is a sine wave. The wave runs (phase moves) and has full amplitude while
 * [playing]; it relaxes to [restingAmplitude] (0 = a flat line) with a spring when paused. The rest of the track is a straight line,
 * [ticks] adds evenly spaced marks, [tickFractions] marks at given places instead (the chapters of a book). Pass [onSeek] to make it a seek bar
 * (tap or drag, fraction 0..1; [onSeekEnd] follows when the finger lifts, so a caller can commit once instead of on every move).
 */
@Composable
fun WavyProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    restingAmplitude: Float = 0f,
    onSeek: ((Float) -> Unit)? = null,
    onSeekEnd: (() -> Unit)? = null,
    ticks: Boolean = false,
    tickFractions: List<Float>? = null,
    height: Dp = 32.dp,
    strokeWidth: Dp = 4.dp,
    waveLength: Dp = 40.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
    showThumb: Boolean = true,
) {
    val fraction = progress.coerceIn(0f, 1f)
    val trackColorOnThumb = MaterialTheme.colorScheme.background
    val amplitudeScale by animateFloatAsState(if (playing) 1f else restingAmplitude.coerceIn(0f, 1f), Motion.spatial(), label = "wave-amplitude")
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                phase = (phase + (now - last) / 1_000_000_000f * (2f * PI.toFloat() / 1.6f)) % (2f * PI.toFloat())
                last = now
            }
        }
    }
    val density = LocalDensity.current
    val stroke = with(density) { strokeWidth.toPx() }
    val wave = with(density) { waveLength.toPx() }
    val maxAmplitude = with(density) { (height / 2 - strokeWidth * 1.5f).toPx().coerceAtLeast(0f) }
    var seekModifier: Modifier = Modifier
    if (onSeek != null) {
        seekModifier = Modifier
            .pointerInput(onSeek, onSeekEnd) {
                detectTapGestures {
                    onSeek((it.x / size.width).coerceIn(0f, 1f))
                    onSeekEnd?.invoke()
                }
            }
            .pointerInput(onSeek, onSeekEnd) {
                detectDragGestures(onDragEnd = { onSeekEnd?.invoke() }, onDragCancel = { onSeekEnd?.invoke() }) { change, _ ->
                    onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds()
            .then(seekModifier)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
    ) {
        val cy = size.height / 2f
        val inset = stroke
        val usable = (size.width - 2 * inset).coerceAtLeast(1f)
        val x = inset + usable * fraction
        // The rest of the track: a straight line (with ticks for the player).
        drawLine(trackColor, Offset(x, cy), Offset(size.width - inset, cy), strokeWidth = stroke, cap = StrokeCap.Round)
        if (ticks || tickFractions != null) {
            val marks = tickFractions ?: (1..10).map { it / 10f }
            for (f in marks) {
                val tx = inset + usable * f.coerceIn(0f, 1f)
                if (tx > x + stroke * 2) drawLine(trackColor, Offset(tx, cy + stroke * 2), Offset(tx, cy + stroke * 3.2f), strokeWidth = stroke / 3f, cap = StrokeCap.Round)
            }
        }
        // The played part: a sine that eases into a straight line at the start so the bar starts clean.
        val amp = maxAmplitude * amplitudeScale
        val path = Path()
        var px = inset
        path.moveTo(px, cy)
        while (px < x) {
            val ease = ((px - inset) / (wave * 0.5f)).coerceIn(0f, 1f)
            path.lineTo(px, cy + amp * ease * sin((px - inset) / wave * 2f * PI.toFloat() - phase))
            px += 2f
        }
        val ease = ((x - inset) / (wave * 0.5f)).coerceIn(0f, 1f)
        val ty = cy + amp * ease * sin((x - inset) / wave * 2f * PI.toFloat() - phase)
        path.lineTo(x, ty)
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        if (showThumb) {
            drawCircle(color, radius = stroke * 2.2f, center = Offset(x, ty))
            drawCircle(trackColorOnThumb, radius = stroke * 0.9f, center = Offset(x, ty))
        }
    }
}

@Preview(widthDp = 360)
@Composable
private fun WavyProgressPreview() = AudiobooksPreviewSurface {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
        WavyProgressBar(0.4f, playing = true)
        WavyProgressBar(0.4f, restingAmplitude = 0.5f, ticks = true)
        WavyProgressBar(0.7f)
    }
}
