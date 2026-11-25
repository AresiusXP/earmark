package xyz.aresius.audiobooks.feature.download

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.SecondaryButton
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.design.WavyProgressBar
import xyz.aresius.audiobooks.core.download.BookDownloadState
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.ReleaseView

/** Asks for the notification permission (Android 13+) before the first download starts; the download starts whatever the answer is. */
@Composable
private fun rememberStartAction(onStart: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onStart() }
    return {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onStart()
        }
    }
}

@Composable
private fun BookDownloadState.buttonLabel(): String = when (this) {
    BookDownloadState.None -> stringResource(R.string.download_button_idle)
    BookDownloadState.Queued -> stringResource(R.string.download_button_queued)
    is BookDownloadState.Preparing -> if (percent != null && percent > 0) stringResource(R.string.download_button_preparing_pct, percent) else stringResource(R.string.download_button_preparing)
    is BookDownloadState.Downloading -> stringResource(R.string.download_button_downloading, (fraction * 100).toInt())
    is BookDownloadState.Paused -> stringResource(R.string.download_button_paused)
    is BookDownloadState.Failed -> stringResource(R.string.download_button_retry)
    is BookDownloadState.Completed -> stringResource(R.string.download_button_done)
}

/**
 * The per-book "Download for offline" pill (Book detail, the player): reflects the book's [BookDownloadState]. [release] is the one to
 * download when the book has no download yet (null disables it). Pair it with [DownloadStatus] for the details.
 */
@Composable
fun DownloadButton(book: Book, release: ReleaseView?, modifier: Modifier = Modifier, viewModel: DownloadControlsViewModel = hiltViewModel()) {
    val state by viewModel.state(book.asin).collectAsStateWithLifecycle()
    val start = rememberStartAction { if (release != null) viewModel.start(book, release) }
    SecondaryButton(
        text = state.buttonLabel(),
        onClick = { if (state is BookDownloadState.Failed) viewModel.retry(book.asin) else start() },
        modifier = modifier,
        enabled = state is BookDownloadState.Failed || (state == BookDownloadState.None && release != null),
    )
}

/**
 * Details under the download button: progress, speed and time left, "waiting for Wi-Fi", the reason of a failure, a Cancel / Delete
 * action, and the message of a start that was refused (no key, no space). Draws nothing when there is nothing to say.
 */
@Composable
fun DownloadStatus(asin: String, modifier: Modifier = Modifier, title: String = "", viewModel: DownloadControlsViewModel = hiltViewModel()) {
    val state by viewModel.state(asin).collectAsStateWithLifecycle()
    val rejected by viewModel.rejected.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val rejectedText = rejected[asin]?.messageOrNull()
    val text = state.statusText() ?: rejectedText
    if (text == null) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (state is BookDownloadState.Downloading) {
            val d = state as BookDownloadState.Downloading
            WavyProgressBar(d.fraction, Modifier.fillMaxWidth(), height = 18.dp, strokeWidth = 3.dp, waveLength = 28.dp, restingAmplitude = 0.5f, showThumb = false)
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            val failed = state is BookDownloadState.Failed || (state == BookDownloadState.None && rejectedText != null)
            if (failed) Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp))
            if (state is BookDownloadState.Completed) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        val s = state
        if (s.isActive || s is BookDownloadState.Failed) {
            TextButton(onClick = { viewModel.delete(asin) }) { Text(stringResource(R.string.downloads_cancel), color = MaterialTheme.colorScheme.onSurface) }
        } else if (s is BookDownloadState.Completed) {
            TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.downloads_delete), color = MaterialTheme.colorScheme.onSurface) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.downloads_delete_book_title)) },
            text = { Text(stringResource(R.string.downloads_delete_book_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(asin)
                }) { Text(stringResource(R.string.downloads_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** The text button on a release card: "Download this release", or the state when this release is the one downloading / downloaded. */
@Composable
fun ReleaseDownloadButton(book: Book, release: ReleaseView, modifier: Modifier = Modifier, viewModel: DownloadControlsViewModel = hiltViewModel()) {
    val state by viewModel.state(book.asin).collectAsStateWithLifecycle()
    val hash by viewModel.releaseHash(book.asin).collectAsStateWithLifecycle()
    val start = rememberStartAction { viewModel.start(book, release) }
    val mine = hash == release.infoHash
    val label = if (mine && state != BookDownloadState.None) state.buttonLabel() else stringResource(R.string.download_button_release)
    val enabled = if (mine) state is BookDownloadState.Failed else state == BookDownloadState.None && viewModel.canDownload(release)
    TextButton(onClick = { if (mine && state is BookDownloadState.Failed) viewModel.retry(book.asin) else start() }, enabled = enabled, modifier = modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurface)
    }
}
