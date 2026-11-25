package xyz.aresius.audiobooks.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.Badge
import xyz.aresius.audiobooks.core.design.BadgeStyle
import xyz.aresius.audiobooks.core.design.DetailTopBar
import xyz.aresius.audiobooks.core.design.ErrorState
import xyz.aresius.audiobooks.core.design.SecondaryButton
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.network.AdminUser
import xyz.aresius.audiobooks.core.network.Invite
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.feature.book.label
import xyz.aresius.audiobooks.feature.common.CodeDialog
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.feature.common.formatInstant
import xyz.aresius.audiobooks.feature.common.message

/** Admin only (the server answers 403 to everybody else): users with disable/enable, and invites. */
@Composable
fun UsersScreen(onBack: () -> Unit, viewModel: UsersViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val errorText = state.error?.message()
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbar.showSnackbar(errorText)
            viewModel.consumeError()
        }
    }
    var confirmToggle by remember { mutableStateOf<AdminUser?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        DetailTopBar(stringResource(R.string.users_title), onBack)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            item { Text(stringResource(R.string.users_users), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = Spacing.screen)) }
            when (val users = state.users) {
                Load.Loading -> item { Text(stringResource(R.string.common_loading), Modifier.padding(horizontal = Spacing.screen), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                is Load.Failed -> item { ErrorState(users.error.message(), onRetry = viewModel::refresh) }
                is Load.Content -> items(users.value, key = { it.id }) { user ->
                    UserRow(user, isSelf = user.id == state.currentUserId, busy = state.busyUserId == user.id, onToggle = { confirmToggle = user })
                }
            }
            item {
                HorizontalDivider(Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.s))
                Text(stringResource(R.string.users_invites), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = Spacing.screen))
            }
            when (val invites = state.invites) {
                Load.Loading -> Unit
                is Load.Failed -> item { ErrorState(invites.error.message(), onRetry = viewModel::refresh) }
                is Load.Content -> {
                    if (invites.value.isEmpty()) {
                        item { Text(stringResource(R.string.users_no_invites), Modifier.padding(horizontal = Spacing.screen), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(invites.value, key = { it.id }) { invite ->
                        InviteRow(invite, busy = state.busyInviteId == invite.id, onRevoke = { viewModel.revokeInvite(invite) })
                    }
                }
            }
            item {
                SecondaryButton(
                    stringResource(R.string.users_create_invite),
                    onClick = { creating = true },
                    enabled = !state.creatingInvite,
                    modifier = Modifier.padding(horizontal = Spacing.screen),
                )
                Text(stringResource(R.string.users_invite_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.screen))
            }
        }
        SnackbarHost(snackbar)
    }

    confirmToggle?.let { user ->
        AlertDialog(
            onDismissRequest = { confirmToggle = null },
            title = { Text(stringResource(if (user.disabled) R.string.users_enable_title else R.string.users_disable_title, user.displayName)) },
            text = { Text(stringResource(if (user.disabled) R.string.users_enable_message else R.string.users_disable_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmToggle = null
                    viewModel.setDisabled(user, !user.disabled)
                }) { Text(stringResource(if (user.disabled) R.string.users_enable else R.string.users_disable)) }
            },
            dismissButton = { TextButton(onClick = { confirmToggle = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (creating) {
        CreateInviteDialog(
            onCreate = { note, hours ->
                creating = false
                viewModel.createInvite(note, hours)
            },
            onDismiss = { creating = false },
        )
    }
    state.createdInvite?.let { invite ->
        CodeDialog(
            title = stringResource(R.string.users_invite_created_title),
            message = stringResource(R.string.users_invite_created_message),
            code = invite.code,
            expiresAt = invite.expiresAt,
            onDismiss = viewModel::dismissCreatedInvite,
        )
    }
}

@Composable
private fun UserRow(user: AdminUser, isSelf: Boolean, busy: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(user.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                if (user.isAdmin) Badge(stringResource(R.string.settings_role_admin), style = BadgeStyle.Accent)
                if (isSelf) Badge(stringResource(R.string.users_you))
                if (user.disabled) Badge(stringResource(R.string.users_disabled))
            }
            val seen = formatInstant(user.lastSeenAt)
            Text(
                stringResource(R.string.users_devices_seen, user.deviceCount, seen.ifEmpty { stringResource(R.string.settings_never_seen) }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Provider.entries.forEach { p ->
                    val has = user.debrid[p.wire] == true
                    Badge(stringResource(if (has) R.string.users_has_key else R.string.users_no_key, p.label()), style = if (has) BadgeStyle.Ok else BadgeStyle.Neutral)
                }
            }
        }
        if (!isSelf) TextButton(onClick = onToggle, enabled = !busy) { Text(stringResource(if (user.disabled) R.string.users_enable else R.string.users_disable)) }
    }
}

@Composable
private fun InviteRow(invite: Invite, busy: Boolean, onRevoke: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(invite.note?.takeIf { it.isNotBlank() } ?: stringResource(R.string.users_invite_no_note), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.users_invite_expires, formatInstant(invite.expiresAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRevoke, enabled = !busy) { Text(stringResource(R.string.users_revoke)) }
    }
}

@Composable
private fun CreateInviteDialog(onCreate: (String, Int?) -> Unit, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("72") }
    val hoursValue = hours.toIntOrNull()?.takeIf { it in 1..720 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.users_create_invite)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 80) note = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.users_invite_note)) },
                )
                OutlinedTextField(
                    value = hours,
                    onValueChange = { hours = it.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.users_invite_hours)) },
                    isError = hoursValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(note, hoursValue) }, enabled = hoursValue != null) { Text(stringResource(R.string.users_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
