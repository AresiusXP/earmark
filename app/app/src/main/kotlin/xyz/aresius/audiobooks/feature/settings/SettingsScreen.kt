package xyz.aresius.audiobooks.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.auth.InputValidation
import xyz.aresius.audiobooks.core.design.Badge
import xyz.aresius.audiobooks.core.design.DynamicColorSupported
import xyz.aresius.audiobooks.core.design.BadgeStyle
import xyz.aresius.audiobooks.core.design.ErrorState
import xyz.aresius.audiobooks.core.design.PillChip
import xyz.aresius.audiobooks.core.design.PrimaryButton
import xyz.aresius.audiobooks.core.design.ScreenTitle
import xyz.aresius.audiobooks.core.design.Section
import xyz.aresius.audiobooks.core.design.SecondaryButton
import xyz.aresius.audiobooks.core.design.SegmentedChoice
import xyz.aresius.audiobooks.core.design.ThemeMode
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.network.Device
import xyz.aresius.audiobooks.core.network.SETTINGS_FORMATS
import xyz.aresius.audiobooks.core.network.SETTINGS_LANGUAGES
import xyz.aresius.audiobooks.core.network.SETTINGS_REGIONS
import xyz.aresius.audiobooks.core.network.SourceMode
import xyz.aresius.audiobooks.feature.bittorrent.BitTorrentCard
import xyz.aresius.audiobooks.feature.bittorrent.BitTorrentRiskText
import xyz.aresius.audiobooks.feature.common.CodeDialog
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.feature.common.formatInstant
import xyz.aresius.audiobooks.feature.common.message
import xyz.aresius.audiobooks.feature.debrid.DebridSection
import xyz.aresius.audiobooks.feature.download.DownloadSettingsSection
import xyz.aresius.audiobooks.feature.onboarding.messageRes
import xyz.aresius.audiobooks.feature.player.PlaybackSection
import xyz.aresius.audiobooks.feature.update.UpdateSection

/** "us" -> "United States" in the user's language ("uk" is Great Britain's region code in ISO terms). */
fun regionLabel(code: String, locale: Locale = Locale.getDefault()): String {
    val region = if (code == "uk") "GB" else code.uppercase()
    return Locale.Builder().setRegion(region).build().getDisplayCountry(locale).ifBlank { code.uppercase() }
}

fun languageLabel(code: String, locale: Locale = Locale.getDefault()): String =
    Locale.forLanguageTag(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }.ifBlank { code }

@Composable
fun SettingsScreen(isAdmin: Boolean, onUsers: () -> Unit, onDownloads: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val errorText = state.error?.message()
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbar.showSnackbar(errorText)
            viewModel.consumeError()
        }
    }
    var renaming by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<Device?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.settings_title))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            val me = state.me
            Section(stringResource(R.string.settings_account)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(me?.displayName.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        if (me != null) {
                            Text(
                                stringResource(if (me.isAdmin) R.string.settings_role_admin else R.string.settings_role_user),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { renaming = true }, enabled = me != null) {
                        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.settings_rename))
                    }
                }
                GoogleAccountCard()
            }
            if (me != null) {
                val s = me.settings
                Section(stringResource(R.string.settings_preferences)) {
                    RegionPicker(s.region, enabled = !state.saving, onPick = viewModel::setRegion)
                    Text(stringResource(R.string.settings_languages), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        SETTINGS_LANGUAGES.forEach { lang ->
                            PillChip(languageLabel(lang), selected = lang in s.languages, onClick = { viewModel.toggleLanguage(lang) })
                        }
                    }
                    Text(stringResource(R.string.settings_format), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        SETTINGS_FORMATS.forEach { f ->
                            val label = when (f) {
                                "m4b" -> R.string.settings_format_m4b
                                "mp3" -> R.string.settings_format_mp3
                                else -> R.string.settings_format_default
                            }
                            PillChip(stringResource(label), selected = f == s.preferFormat, onClick = { viewModel.setPreferredFormat(f) })
                        }
                    }
                    Text(stringResource(R.string.settings_source_mode), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        SourceMode.ALL.forEach { mode ->
                            if (mode != SourceMode.P2P || state.p2pSourceModeAvailable || s.sourceMode == SourceMode.P2P) {
                                val label = when (mode) {
                                    SourceMode.DEBRID -> R.string.settings_source_debrid
                                    SourceMode.P2P -> R.string.settings_source_p2p
                                    else -> R.string.settings_source_auto
                                }
                                PillChip(stringResource(label), selected = mode == s.sourceMode, onClick = { viewModel.setSourceMode(mode) })
                            }
                        }
                    }
                    Text(
                        stringResource(
                            when (s.sourceMode) {
                                SourceMode.DEBRID -> R.string.settings_source_debrid_help
                                SourceMode.P2P -> R.string.settings_source_p2p_help
                                else -> R.string.settings_source_auto_help
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!state.p2pSourceModeAvailable) {
                        Text(stringResource(R.string.settings_source_p2p_locked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Section(stringResource(R.string.settings_playback)) { PlaybackSection() }
            Section(stringResource(R.string.settings_appearance)) {
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelLarge)
                SegmentedChoice(
                    options = listOf(
                        ThemeMode.System to stringResource(R.string.settings_theme_system),
                        ThemeMode.Light to stringResource(R.string.settings_theme_light),
                        ThemeMode.Dark to stringResource(R.string.settings_theme_dark),
                    ),
                    selected = state.themeMode,
                    onSelect = viewModel::setThemeMode,
                )
                Text(
                    stringResource(
                        when (state.themeMode) {
                            ThemeMode.System -> R.string.settings_theme_system_help
                            ThemeMode.Light -> R.string.settings_theme_light_help
                            ThemeMode.Dark -> R.string.settings_theme_dark_help
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (DynamicColorSupported) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_dynamic_color), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.settings_dynamic_color_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.dynamicColor, onCheckedChange = viewModel::setDynamicColor)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_player_follows_theme), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.settings_player_follows_theme_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.playerFollowsTheme, onCheckedChange = viewModel::setPlayerFollowsTheme)
                }
            }
            Section(stringResource(R.string.settings_debrid)) { DebridSection() }
            Section(stringResource(R.string.settings_bittorrent)) {
                BitTorrentRiskText()
                BitTorrentCard()
            }
            DownloadSettingsSection(onOpenDownloads = onDownloads)
            Section(stringResource(R.string.settings_devices)) {
                when (val devices = state.devices) {
                    Load.Loading -> Text(stringResource(R.string.common_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is Load.Failed -> ErrorState(devices.error.message(), onRetry = viewModel::loadDevices)
                    is Load.Content -> devices.value.forEach { device ->
                        DeviceRow(device, busy = state.busyDeviceId == device.id, onRevoke = { revoking = device })
                    }
                }
                SecondaryButton(stringResource(R.string.settings_link_device), onClick = viewModel::createLinkCode, enabled = !state.creatingLinkCode)
                Text(stringResource(R.string.settings_link_device_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isAdmin) {
                Section(stringResource(R.string.settings_admin)) {
                    SecondaryButton(stringResource(R.string.settings_manage_users), onClick = onUsers)
                }
            }
            Section(stringResource(R.string.settings_about)) { UpdateSection() }
            Section(stringResource(R.string.settings_session)) {
                PrimaryButton(stringResource(R.string.settings_sign_out), onClick = { confirmSignOut = true })
            }
            HorizontalDivider(Modifier.padding(top = Spacing.m))
        }
        SnackbarHost(snackbar)
    }

    if (renaming) {
        RenameDialog(
            current = state.me?.displayName.orEmpty(),
            onConfirm = { name ->
                viewModel.rename(name)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
    state.linkCode?.let { code ->
        CodeDialog(
            title = stringResource(R.string.settings_link_code_title),
            message = stringResource(R.string.settings_link_code_message),
            code = code.linkCode,
            expiresAt = code.expiresAt,
            onDismiss = viewModel::dismissLinkCode,
        )
    }
    revoking?.let { device ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(stringResource(if (device.current) R.string.settings_revoke_current_title else R.string.settings_revoke_title, device.name)) },
            text = { Text(stringResource(if (device.current) R.string.settings_revoke_current_message else R.string.settings_revoke_message)) },
            confirmButton = {
                TextButton(onClick = {
                    revoking = null
                    viewModel.revokeDevice(device.id)
                }) { Text(stringResource(R.string.settings_revoke)) }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.settings_sign_out_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.signOut()
                }) { Text(stringResource(R.string.settings_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun RegionPicker(region: String, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(stringResource(R.string.settings_region), style = MaterialTheme.typography.labelLarge)
    Column {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(regionLabel(region), modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SETTINGS_REGIONS.forEach { code ->
                DropdownMenuItem(text = { Text(regionLabel(code)) }, onClick = {
                    open = false
                    onPick(code)
                })
            }
        }
    }
}

@Composable
private fun DeviceRow(device: Device, busy: Boolean, onRevoke: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                if (device.current) Badge(stringResource(R.string.settings_this_device), style = BadgeStyle.Accent)
            }
            val seen = formatInstant(device.lastSeenAt)
            Text(
                if (seen.isNotEmpty()) stringResource(R.string.settings_last_seen, seen) else stringResource(R.string.settings_never_seen),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRevoke, enabled = !busy) { Text(stringResource(R.string.settings_revoke)) }
    }
}

@Composable
private fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    val problem = InputValidation.validateDisplayName(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_rename)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.onboarding_display_name)) },
                isError = problem != null && text.isNotEmpty(),
                supportingText = problem?.takeIf { text.isNotEmpty() }?.let { { Text(stringResource(it.messageRes())) } },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = problem == null) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
