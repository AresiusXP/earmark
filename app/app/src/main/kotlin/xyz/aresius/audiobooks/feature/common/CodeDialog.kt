package xyz.aresius.audiobooks.feature.common

import android.content.ClipData
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.R

/**
 * Shows a one-time code (invite or link code) with a Copy button. The code is not shown again once the dialog closes, so
 * the copy is marked sensitive (Android hides it from the clipboard preview) where the platform supports that.
 */
@Composable
fun CodeDialog(title: String, message: String, code: String, expiresAt: String?, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Text(code, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.primary)
                val expires = formatInstant(expiresAt)
                if (expires.isNotEmpty()) {
                    Text(stringResource(R.string.code_expires, expires), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(sensitiveClip(code))) }
            }) { Text(stringResource(R.string.code_copy)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
    )
}

private fun sensitiveClip(code: String): ClipData {
    val clip = ClipData.newPlainText("code", code)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    return clip
}
