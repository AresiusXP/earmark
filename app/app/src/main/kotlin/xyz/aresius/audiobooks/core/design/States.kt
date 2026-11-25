package xyz.aresius.audiobooks.core.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import xyz.aresius.audiobooks.R

/** Nothing to show: a serif line, a short quiet explanation, no illustration. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    TypographicMessage(modifier, title, message, MaterialTheme.colorScheme.onSurface, withIcon = false, action = action)
}

/** An error: a small icon and the words (never color alone), a witty-but-short serif title, and Retry. */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.common_error_title),
    retryLabel: String = stringResource(R.string.common_retry),
) {
    TypographicMessage(modifier, title, message, MaterialTheme.colorScheme.error, withIcon = true) {
        PrimaryButton(retryLabel, onClick = onRetry, modifier = Modifier.width(220.dp))
    }
}

@Composable
private fun TypographicMessage(
    modifier: Modifier,
    title: String,
    message: String?,
    titleColor: androidx.compose.ui.graphics.Color,
    withIcon: Boolean,
    action: (@Composable () -> Unit)?,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.xl),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (withIcon) Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = titleColor, modifier = Modifier.size(28.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Start)
        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodyLarge, color = if (withIcon) titleColor else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) Box(Modifier.padding(top = Spacing.m)) { action() }
    }
}

/** Animated placeholder block: size it with the modifier (e.g. `LoadingShimmer(Modifier.size(144.dp))`). */
@Composable
fun LoadingShimmer(modifier: Modifier = Modifier, shape: Shape = CoverShape) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = ColorMath.mix(base, MaterialTheme.colorScheme.onSurface, 0.08f)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerProgress",
    )
    Box(
        modifier
            .clip(shape)
            .drawWithCache {
                val w = size.width.coerceAtLeast(1f)
                val start = -w + 2 * w * progress
                val brush = Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(start, 0f),
                    end = Offset(start + w, 0f),
                )
                onDrawBehind { drawRect(brush) }
            },
    )
}

@Composable
fun ShimmerTrackRows(count: Int = 6, modifier: Modifier = Modifier) {
    Column(modifier) {
        repeat(count) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LoadingShimmer(Modifier.size(72.dp), RoundedCornerShape(18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoadingShimmer(Modifier.height(16.dp).width(180.dp), RoundedCornerShape(6.dp))
                    LoadingShimmer(Modifier.height(12.dp).width(110.dp), RoundedCornerShape(6.dp))
                }
            }
        }
    }
}

@Composable
fun ShimmerShelf(count: Int = 3, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = Spacing.screen), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(count) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LoadingShimmer(Modifier.size(160.dp))
                LoadingShimmer(Modifier.height(16.dp).width(110.dp), RoundedCornerShape(6.dp))
                LoadingShimmer(Modifier.height(12.dp).width(70.dp), RoundedCornerShape(6.dp))
            }
        }
    }
}

@Preview(name = "Paper", heightDp = 700)
@Preview(name = "Ink", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, heightDp = 700)
@Composable
private fun StatesPreview() = AudiobooksPreviewSurface {
    Column(Modifier.fillMaxSize()) {
        EmptyState("Quiet on the shelves", message = "Nothing here yet.")
        ErrorState("Couldn't reach the server.", onRetry = {})
        ShimmerShelf()
    }
}
