package xyz.aresius.audiobooks.feature.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.DetailTopBar
import xyz.aresius.audiobooks.core.design.EmptyState
import xyz.aresius.audiobooks.core.design.ScreenTitle
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.design.WavyProgressBar
import xyz.aresius.audiobooks.core.download.BookDownloadState
import xyz.aresius.audiobooks.core.download.DownloadedBook
import xyz.aresius.audiobooks.core.download.StorageInfo
import xyz.aresius.audiobooks.core.download.WaitReason
import xyz.aresius.audiobooks.feature.common.BookRow
import xyz.aresius.audiobooks.feature.common.formatSize

@Composable
fun DownloadsScreen(onBack: () -> Unit, onBook: (String) -> Unit, viewModel: DownloadsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmAll by remember { mutableStateOf(false) }
    var confirmBook by remember { mutableStateOf<DownloadedBook?>(null) }

    Column(Modifier.fillMaxSize()) {
        DetailTopBar(
            title = "",
            onBack = onBack,
            actions = {
                if (state.books.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text(stringResource(R.string.downloads_delete_all), color = MaterialTheme.colorScheme.onSurface) }
            },
        )
        ScreenTitle(stringResource(R.string.downloads_title))
        state.storage?.let { StorageSummary(it, Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.s)) }
        if (state.loaded && state.books.isEmpty()) {
            EmptyState(stringResource(R.string.downloads_empty_title), message = stringResource(R.string.downloads_empty_message))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.books, key = { it.book.asin }) { item ->
                    DownloadRow(
                        item, onClick = { onBook(item.book.asin) }, onDelete = { confirmBook = item }, onRetry = { viewModel.retry(item.book.asin) },
                        onPause = { viewModel.pause(item.book.asin) }, onResume = { viewModel.resume(item.book.asin) },
                    )
                }
            }
        }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.downloads_delete_all_title)) },
            text = { Text(stringResource(R.string.downloads_delete_all_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    viewModel.deleteAll()
                }) { Text(stringResource(R.string.downloads_delete_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    confirmBook?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmBook = null },
            title = { Text(stringResource(R.string.downloads_delete_book_title)) },
            text = { Text(stringResource(R.string.downloads_delete_book_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBook = null
                    viewModel.delete(item.book.asin)
                }) { Text(stringResource(R.string.downloads_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmBook = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
fun StorageSummary(info: StorageInfo, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            if (info.limitBytes > 0) stringResource(R.string.downloads_storage_used, formatSize(info.usedBytes), formatSize(info.limitBytes))
            else stringResource(R.string.downloads_storage_used_unlimited, formatSize(info.usedBytes)),
            style = MaterialTheme.typography.titleSmall,
        )
        if (info.limitBytes > 0) {
            WavyProgressBar((info.usedBytes.toFloat() / info.limitBytes).coerceIn(0f, 1f), Modifier.fillMaxWidth(), height = 18.dp, strokeWidth = 3.dp, waveLength = 28.dp, restingAmplitude = 0.5f, showThumb = false)
        }
        Text(stringResource(R.string.downloads_storage_free, formatSize(info.freeBytes)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DownloadRow(item: DownloadedBook, onClick: () -> Unit, onDelete: () -> Unit, onRetry: () -> Unit, onPause: () -> Unit, onResume: () -> Unit) {
    val state = item.state
    BookRow(
        book = item.book,
        onClick = onClick,
        supporting = state.statusText(),
        progress = (state as? BookDownloadState.Downloading)?.fraction,
        trailing = {
            if (state is BookDownloadState.Failed) TextButton(onClick = onRetry) { Text(stringResource(R.string.download_button_retry), color = MaterialTheme.colorScheme.onSurface) }
            if (state.isActive) {
                val userPaused = state is BookDownloadState.Paused && state.waitingFor == WaitReason.User
                IconButton(onClick = if (userPaused) onResume else onPause) {
                    Icon(
                        if (userPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = stringResource(if (userPaused) R.string.downloads_row_resume else R.string.downloads_row_pause, item.book.title),
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.downloads_row_delete, item.book.title))
            }
        },
    )
}
