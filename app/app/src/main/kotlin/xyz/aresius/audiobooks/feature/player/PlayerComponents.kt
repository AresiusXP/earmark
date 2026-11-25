package xyz.aresius.audiobooks.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.PillShape
import xyz.aresius.audiobooks.core.design.SquircleShape
import xyz.aresius.audiobooks.core.design.TabularFigures

/** Outlined pill under the controls: speed, sleep timer, bookmark. */
@Composable
fun PlayerPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, highlighted: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(PillShape)
            .border(BorderStroke(1.5.dp, if (highlighted) scheme.primary else scheme.outline), PillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (highlighted) scheme.primary else scheme.onBackground, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.merge(TabularFigures), color = scheme.onBackground, maxLines = 1)
    }
}

/** The squarish skip button: a "replay" arrow with the number of seconds inside. */
@Composable
fun SkipButton(seconds: Int, forward: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 68.dp) {
    val description = stringResource(if (forward) R.string.player_skip_forward_n else R.string.player_skip_back_n, seconds)
    Box(
        modifier
            .size(size)
            .clip(SquircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Replay,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(size * 0.62f).then(if (forward) Modifier.mirrored() else Modifier),
        )
        Text(seconds.toString(), style = MaterialTheme.typography.labelMedium.merge(TabularFigures).copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onBackground)
    }
}

private fun Modifier.mirrored(): Modifier = scale(scaleX = -1f, scaleY = 1f)

/** The big round persimmon button: play, pause, or a spinner while the book is loading or buffering. */
@Composable
fun PlayPauseButton(playing: Boolean, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 88.dp, shape: Shape = androidx.compose.foundation.shape.CircleShape) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.size(size).clip(shape).background(scheme.primary).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(size * 0.5f), color = scheme.onPrimary, strokeWidth = 3.dp)
        } else {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play),
                tint = scheme.onPrimary,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}


/**
 * "-16:09:28 left" for the whole book. While some file lengths are still estimates the label starts with "~" and carries a small info
 * button: a tap or a long press explains why it is approximate (the lengths become exact as the files play).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeLeftLabel(remainingMs: Long, estimated: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val text = (if (estimated) "~" else "") + stringResource(R.string.player_time_left, formatRemaining(remainingMs))
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge.merge(TabularFigures), color = color)
        if (estimated) {
            val tooltip = rememberTooltipState()
            val scope = rememberCoroutineScope()
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(stringResource(R.string.player_estimate_explainer)) } },
                state = tooltip,
            ) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = stringResource(R.string.player_estimate_info),
                    tint = color,
                    modifier = Modifier.padding(start = 2.dp).size(28.dp).clip(CircleShape).clickable(role = Role.Button) { scope.launch { tooltip.show() } }.padding(5.dp),
                )
            }
        }
    }
}
