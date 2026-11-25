package xyz.aresius.audiobooks.feature.debrid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.Badge
import xyz.aresius.audiobooks.core.design.BadgeStyle
import xyz.aresius.audiobooks.core.design.ErrorState
import xyz.aresius.audiobooks.core.design.LoadingShimmer
import xyz.aresius.audiobooks.core.design.PrimaryButton
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.network.ErrorCode
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.feature.book.label
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.feature.common.formatInstant
import xyz.aresius.audiobooks.feature.common.message

/** Both providers with their key status; used by the onboarding Debrid step and by Settings. Never shows a stored key (the server never returns one). */
@Composable
fun DebridSection(modifier: Modifier = Modifier, viewModel: DebridViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        when (val load = state.load) {
            Load.Loading -> LoadingShimmer(Modifier.fillMaxWidth().height(96.dp), RoundedCornerShape(12.dp))
            is Load.Failed -> ErrorState(load.error.message(), onRetry = viewModel::refresh)
            is Load.Content -> state.providers.forEach { row ->
                ProviderCard(
                    row = row,
                    onStartEditing = { viewModel.startEditing(row.provider) },
                    onCancel = { viewModel.cancelEditing(row.provider) },
                    onKeyChange = { viewModel.onKeyChange(row.provider, it) },
                    onSave = { viewModel.save(row.provider) },
                    onRecheck = { viewModel.recheck(row.provider) },
                    onRemove = { viewModel.remove(row.provider) },
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    row: ProviderUi,
    onStartEditing: () -> Unit,
    onCancel: () -> Unit,
    onKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onRecheck: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(row.provider.label(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (row.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                StatusBadge(row)
            }
            val status = row.status
            if (status != null && status.configured) {
                val validated = formatInstant(status.validatedAt)
                if (validated.isNotEmpty()) {
                    Text(stringResource(R.string.debrid_checked_at, validated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (status.status != "ok" && !status.lastError.isNullOrBlank()) {
                    Text(status.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (status.sharedWithOthers) {
                    Text(stringResource(R.string.debrid_shared), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (row.editing) {
                OutlinedTextField(
                    value = row.keyInput,
                    onValueChange = onKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.debrid_key_label)) },
                    supportingText = { Text(stringResource(R.string.debrid_key_help, row.provider.label())) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    isError = row.error != null,
                    enabled = !row.busy,
                )
                row.error?.let { Text(debridErrorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedButton(onClick = onCancel, enabled = !row.busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_cancel)) }
                    PrimaryButton(stringResource(R.string.debrid_validate_save), onClick = onSave, enabled = row.canSave, loading = row.busy, modifier = Modifier.weight(1f))
                }
            } else {
                row.error?.let { Text(debridErrorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    if (row.configured) {
                        OutlinedButton(onClick = onStartEditing, enabled = !row.busy) { Text(stringResource(R.string.debrid_replace)) }
                        OutlinedButton(onClick = onRecheck, enabled = !row.busy) { Text(stringResource(R.string.debrid_check)) }
                        TextButton(onClick = { confirmRemove = true }, enabled = !row.busy) { Text(stringResource(R.string.debrid_remove)) }
                    } else {
                        OutlinedButton(onClick = onStartEditing, enabled = !row.busy) { Text(stringResource(R.string.debrid_add)) }
                    }
                }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.debrid_remove_title, row.provider.label())) },
            text = { Text(stringResource(R.string.debrid_remove_message)) },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() }) { Text(stringResource(R.string.debrid_remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun StatusBadge(row: ProviderUi) {
    val status = row.status
    when {
        status == null || !status.configured -> Badge(stringResource(R.string.debrid_status_none))
        status.status == "ok" -> Badge(stringResource(R.string.debrid_status_ok), style = BadgeStyle.Ok)
        status.status == "invalid" -> Badge(stringResource(R.string.debrid_status_invalid), style = BadgeStyle.Accent)
        else -> Badge(stringResource(R.string.debrid_status_error), style = BadgeStyle.Accent)
    }
}

@Composable
private fun debridErrorText(error: ApiError): String =
    if (error is ApiError.Http && error.code == ErrorCode.BadDebridKey) stringResource(R.string.debrid_rejected) else error.message()
