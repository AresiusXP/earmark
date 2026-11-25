package xyz.aresius.audiobooks.feature.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.PillChip
import xyz.aresius.audiobooks.core.design.SecondaryButton
import xyz.aresius.audiobooks.core.design.Section
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.core.download.DownloadPrefs
import xyz.aresius.audiobooks.core.download.DownloadSettings
import xyz.aresius.audiobooks.core.download.DownloadsRepository
import xyz.aresius.audiobooks.core.download.StorageInfo
import xyz.aresius.audiobooks.feature.common.formatSize

@HiltViewModel
class DownloadSettingsViewModel @Inject constructor(
    private val settings: DownloadSettings,
    downloads: DownloadsRepository,
) : ViewModel() {
    val prefs: StateFlow<DownloadPrefs> = settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadPrefs())
    val storage: StateFlow<StorageInfo?> = downloads.storageInfo.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setMobile(value: Boolean) = viewModelScope.launch { settings.setAllowMobileData(value) }
    fun setLimit(bytes: Long) = viewModelScope.launch { settings.setStorageLimit(bytes) }
    fun setAuto(value: Boolean) = viewModelScope.launch { settings.setAutoDownloadWhileListening(value) }
    fun setAutoDelete(days: Int) = viewModelScope.launch { settings.setAutoDeleteAfterDays(days) }
}

/** The "Downloads" block of Settings (own file: the settings screen only adds one `Section` line that calls this). */
@Composable
fun DownloadSettingsSection(onOpenDownloads: () -> Unit, viewModel: DownloadSettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    Section(stringResource(R.string.downloads_settings_title)) {
        storage?.let { Text(stringResource(R.string.downloads_settings_usage, formatSize(it.usedBytes)), style = MaterialTheme.typography.bodyMedium) }
        SwitchRow(stringResource(R.string.downloads_settings_mobile), stringResource(R.string.downloads_settings_mobile_help), prefs.allowMobileData, viewModel::setMobile)
        Text(stringResource(R.string.downloads_settings_limit), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            DownloadPrefs.LIMIT_CHOICES.forEach { bytes ->
                PillChip(
                    if (bytes == 0L) stringResource(R.string.downloads_settings_limit_none) else formatSize(bytes).replace(".0 ", " "),
                    selected = prefs.storageLimitBytes == bytes,
                    onClick = { viewModel.setLimit(bytes) },
                )
            }
        }
        Text(stringResource(R.string.downloads_settings_limit_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow(stringResource(R.string.downloads_settings_auto), stringResource(R.string.downloads_settings_auto_help), prefs.autoDownloadWhileListening, viewModel::setAuto)
        Text(stringResource(R.string.downloads_settings_auto_delete), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            DownloadPrefs.AUTO_DELETE_CHOICES.forEach { days ->
                PillChip(
                    when (days) {
                        0 -> stringResource(R.string.downloads_settings_auto_delete_never)
                        1 -> stringResource(R.string.downloads_settings_auto_delete_day)
                        else -> stringResource(R.string.downloads_settings_auto_delete_days, days)
                    },
                    selected = prefs.autoDeleteAfterDays == days,
                    onClick = { viewModel.setAutoDelete(days) },
                )
            }
        }
        SecondaryButton(stringResource(R.string.downloads_settings_manage), onClick = onOpenDownloads)
    }
}

@Composable
private fun SwitchRow(title: String, help: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
