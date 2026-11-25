package xyz.aresius.audiobooks.feature.update

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.update.PackageApkInstaller
import xyz.aresius.audiobooks.core.update.UpdateError
import xyz.aresius.audiobooks.core.update.UpdatePhase
import xyz.aresius.audiobooks.core.update.UpdateState
import xyz.aresius.audiobooks.feature.common.formatSize

@Composable
internal fun UpdateError.text(): String = when (this) {
    UpdateError.NotAvailable -> stringResource(R.string.update_error_not_available)
    UpdateError.Network -> stringResource(R.string.update_error_network)
    UpdateError.Corrupt -> stringResource(R.string.update_error_corrupt)
    is UpdateError.Server -> stringResource(R.string.update_error_server, code)
    UpdateError.SignatureMismatch -> stringResource(R.string.update_error_signature)
    UpdateError.InstallStorage -> stringResource(R.string.update_error_storage)
    is UpdateError.InstallFailed -> stringResource(R.string.update_error_install)
}

/** What to show and tap for the current phase of an offered update; used by the banner and the Settings section. */
@Composable
internal fun UpdateActions(state: UpdateState, viewModel: UpdateViewModel, onDismiss: (() -> Unit)? = null) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        when (val phase = state.phase) {
            UpdatePhase.Idle -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(onClick = viewModel::download) { Text(stringResource(R.string.update_download_install)) }
                if (onDismiss != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
            }
            is UpdatePhase.Downloading -> {
                if (phase.total > 0) LinearProgressIndicator(progress = { phase.bytes.toFloat() / phase.total }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(
                        stringResource(R.string.update_downloading, formatSize(phase.bytes), formatSize(phase.total)),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::cancelDownload) { Text(stringResource(R.string.common_cancel)) }
                }
            }
            UpdatePhase.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(onClick = viewModel::install) { Text(stringResource(R.string.update_install)) }
                if (onDismiss != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
            }
            is UpdatePhase.NeedsInstallPermission -> {
                Text(stringResource(R.string.update_needs_permission), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedButton(onClick = {
                        viewModel.unknownSourcesSettingsOpened()
                        context.startActivity(PackageApkInstaller.unknownSourcesSettings(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text(stringResource(R.string.update_open_settings)) }
                    TextButton(onClick = viewModel::cancelInstallPermissionPrompt) { Text(stringResource(R.string.common_cancel)) }
                }
            }
            UpdatePhase.Installing -> Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.bodySmall)
            is UpdatePhase.Failed -> {
                Text(phase.error.text(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = viewModel::download) { Text(stringResource(R.string.common_retry)) }
            }
        }
    }
}

