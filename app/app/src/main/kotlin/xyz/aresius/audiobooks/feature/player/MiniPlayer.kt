package xyz.aresius.audiobooks.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.CoverImage
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.design.WavyProgressBar
import xyz.aresius.audiobooks.core.player.PlayerPhase
import xyz.aresius.audiobooks.core.player.PlayerState

/** The player strip above the bottom bar on every screen: cover, title and chapter (or state), skip back and play/pause; a tap opens the player. */
@Composable
fun MiniPlayer(visible: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier, viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = visible && state.hasBook, enter = expandVertically(), exit = shrinkVertically(), modifier = modifier) {
        MiniPlayerContent(state, onOpen, onToggle = viewModel::togglePlay, onSkipBack = viewModel::skipBack)
    }
}

@Composable
private fun MiniPlayerContent(state: PlayerState, onOpen: () -> Unit, onToggle: () -> Unit, onSkipBack: () -> Unit) {
    val book = state.book
    val phase = state.phase
    val line = when (phase) {
        PlayerPhase.Idle, PlayerPhase.Loading -> stringResource(R.string.player_loading)
        is PlayerPhase.Preparing -> phase.progress?.let { stringResource(R.string.player_preparing_progress, it) } ?: stringResource(R.string.player_preparing_title)
        is PlayerPhase.Failed -> phase.text()
        PlayerPhase.Ready -> state.chapter?.displayTitle().orEmpty()
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.s, vertical = Spacing.xs)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CoverImage(book?.coverUrl, Modifier.size(46.dp), shape = RoundedCornerShape(12.dp), contentDescription = book?.title)
            Column(Modifier.weight(1f)) {
                Text(book?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (phase == PlayerPhase.Ready && !state.restored) {
                SkipButton(seconds = (state.skipBackMs / 1000).toInt(), forward = false, onClick = onSkipBack, size = 44.dp)
            }
            PlayPauseButton(playing = state.playing, busy = state.buffering || phase == PlayerPhase.Loading || phase is PlayerPhase.Preparing, onClick = onToggle, size = 48.dp)
        }
        Box(Modifier.padding(horizontal = 14.dp)) {
            WavyProgressBar(
                progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
                playing = state.playing,
                restingAmplitude = 0.4f,
                height = 14.dp,
                strokeWidth = 2.dp,
                waveLength = 24.dp,
                showThumb = false,
            )
        }
    }
}
