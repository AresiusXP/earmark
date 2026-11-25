package xyz.aresius.audiobooks.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.SecondaryButton
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.feature.common.message
import xyz.aresius.audiobooks.feature.onboarding.message

/** The Google part of Settings > Account: which Google account is linked (with Unlink), or a button to link one. */
@Composable
fun GoogleAccountCard(viewModel: GoogleAccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.hidden) return
    val context = LocalContext.current
    val available = remember(context) { viewModel.available(context) }
    var confirmUnlink by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.settings_google_title), style = MaterialTheme.typography.labelLarge)
        if (state.google.linked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.google.email?.let { stringResource(R.string.settings_google_linked, it) } ?: stringResource(R.string.settings_google_linked_no_email),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirmUnlink = true }, enabled = !state.busy) { Text(stringResource(R.string.settings_google_unlink)) }
            }
        } else {
            Text(stringResource(R.string.settings_google_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (available) {
                SecondaryButton(stringResource(R.string.settings_google_link), onClick = { viewModel.link(context) }, enabled = !state.busy)
            } else {
                Text(stringResource(R.string.google_error_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        (state.error?.message() ?: state.apiError?.message())?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (confirmUnlink) {
        AlertDialog(
            onDismissRequest = { confirmUnlink = false },
            title = { Text(stringResource(R.string.settings_google_unlink_title)) },
            text = { Text(stringResource(R.string.settings_google_unlink_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnlink = false
                    viewModel.unlink()
                }) { Text(stringResource(R.string.settings_google_unlink)) }
            },
            dismissButton = { TextButton(onClick = { confirmUnlink = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
