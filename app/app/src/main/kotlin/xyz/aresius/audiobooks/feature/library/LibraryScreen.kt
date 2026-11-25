package xyz.aresius.audiobooks.feature.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.EmptyState
import xyz.aresius.audiobooks.core.design.ErrorState
import xyz.aresius.audiobooks.core.design.PillChip
import xyz.aresius.audiobooks.core.design.ScreenTitle
import xyz.aresius.audiobooks.core.design.ShimmerTrackRows
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.network.LibraryEntry
import xyz.aresius.audiobooks.core.network.LibraryStatus
import xyz.aresius.audiobooks.feature.common.BookRow
import xyz.aresius.audiobooks.feature.common.formatRuntime
import xyz.aresius.audiobooks.feature.common.message
import xyz.aresius.audiobooks.feature.common.progressFraction

@StringRes
internal fun statusLabel(status: String): Int = when (status) {
    LibraryStatus.LISTENING -> R.string.library_status_listening
    LibraryStatus.FINISHED -> R.string.library_status_finished
    else -> R.string.library_status_saved
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBook: (String) -> Unit, onPlay: (LibraryEntry) -> Unit, viewModel: LibraryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.library_title))
        Row(Modifier.padding(horizontal = Spacing.screen), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            PillChip(stringResource(R.string.library_filter_all), selected = state.filter == null, onClick = { viewModel.setFilter(null) })
            LibraryStatus.ALL.forEach { status ->
                PillChip(stringResource(statusLabel(status)), selected = state.filter == status, onClick = { viewModel.setFilter(status) })
            }
        }
        val error = state.error
        when {
            !state.loaded && state.entries.isEmpty() -> ShimmerTrackRows(modifier = Modifier.padding(top = Spacing.m))
            error != null && state.entries.isEmpty() -> ErrorState(error.message(), onRetry = viewModel::refresh)
            else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                val visible = state.visible
                if (visible.isEmpty()) {
                    EmptyState(
                        stringResource(if (state.entries.isEmpty()) R.string.library_empty_title else R.string.library_empty_filter_title),
                        message = stringResource(if (state.entries.isEmpty()) R.string.library_empty_message else R.string.library_empty_filter_message),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (error != null) {
                            item {
                                Text(
                                    stringResource(R.string.library_stale),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
                                )
                            }
                        }
                        items(visible, key = { it.book.asin }) { entry -> LibraryRow(entry, onClick = { onBook(entry.book.asin) }, onPlay = { onPlay(entry) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(entry: LibraryEntry, onClick: () -> Unit, onPlay: () -> Unit) {
    val progress = progressFraction(entry.positionMs, entry.book.runtimeMin)
    val percent = (progress * 100).toInt()
    val runtime = formatRuntime(entry.book.runtimeMin)
    val status = stringResource(statusLabel(entry.status))
    val supporting = if (entry.status == LibraryStatus.LISTENING && percent > 0) {
        stringResource(R.string.library_row_progress, status, percent, runtime)
    } else {
        stringResource(R.string.library_row_plain, status, runtime)
    }
    BookRow(entry.book, onClick = onClick, supporting = supporting, progress = if (entry.status == LibraryStatus.LISTENING) progress else null) {
        IconButton(onClick = onPlay) {
            Icon(Icons.Rounded.PlayCircle, contentDescription = stringResource(R.string.library_play, entry.book.title), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }
    }
}
