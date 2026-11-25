package xyz.aresius.audiobooks.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.Spacing

/** Settings > About: the installed version, "Check now" and, when one is offered, the update with its actions. */
@Composable
fun UpdateSection(viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            stringResource(R.string.settings_version, state.installedVersionName, state.installedVersionCode),
            style = MaterialTheme.typography.bodyMedium,
        )
        val available = state.available
        if (available != null) {
            Text(stringResource(R.string.update_banner_title, available.versionName), style = MaterialTheme.typography.titleSmall)
            UpdateActions(state, viewModel)
        } else if (state.checking) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            if (state.lastCheckedAtMs > 0) Text(stringResource(R.string.update_up_to_date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = viewModel::checkNow) { Text(stringResource(R.string.update_check_now)) }
        }
    }
}
