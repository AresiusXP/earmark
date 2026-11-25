package xyz.aresius.audiobooks.core.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Inbox
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import xyz.aresius.audiobooks.R

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector = Icons.Rounded.Inbox,
    action: (@Composable () -> Unit)? = null,
) {
    CenteredMessage(modifier, icon, title, message, MaterialTheme.colorScheme.onSurfaceVariant, action)
}

/** Error message with a Retry button. */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.common_error_title),
    retryLabel: String = stringResource(R.string.common_retry),
) {
    CenteredMessage(modifier, Icons.Rounded.CloudOff, title, message, MaterialTheme.colorScheme.error) {
        PrimaryButton(retryLabel, onClick = onRetry, modifier = Modifier.width(160.dp))
    }
}

@Composable
private fun CenteredMessage(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    message: String?,
    iconTint: androidx.compose.ui.graphics.Color,
    action: (@Composable () -> Unit)?,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(72.dp).clip(RoundShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(34.dp)) }
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Box(Modifier.padding(top = 8.dp)) { action() }
        }
    }
}

/** Animated placeholder block: size it with the modifier (e.g. `LoadingShimmer(Modifier.size(144.dp))`). */
@Composable
fun LoadingShimmer(modifier: Modifier = Modifier, shape: Shape = CoverShape) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = base.copy(red = base.red + 0.07f, green = base.green + 0.07f, blue = base.blue + 0.09f)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
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
                Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LoadingShimmer(Modifier.size(52.dp), RoundedCornerShape(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoadingShimmer(Modifier.height(14.dp).width(180.dp), RoundedCornerShape(4.dp))
                    LoadingShimmer(Modifier.height(12.dp).width(110.dp), RoundedCornerShape(4.dp))
                }
            }
        }
    }
}

@Composable
fun ShimmerShelf(count: Int = 4, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = Spacing.screen), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LoadingShimmer(Modifier.size(144.dp))
                LoadingShimmer(Modifier.height(14.dp).width(100.dp), RoundedCornerShape(4.dp))
                LoadingShimmer(Modifier.height(12.dp).width(70.dp), RoundedCornerShape(4.dp))
            }
        }
    }
}

@Preview(heightDp = 700)
@Composable
private fun StatesPreview() = AudiobooksPreviewSurface {
    Column(Modifier.fillMaxSize()) {
        EmptyState("Nothing here yet", message = "Search for something to get started.")
        ErrorState("Couldn't reach the server.", onRetry = {})
        ShimmerShelf()
        ShimmerTrackRows(3)
    }
}
