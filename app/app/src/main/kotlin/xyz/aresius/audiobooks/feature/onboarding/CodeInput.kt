package xyz.aresius.audiobooks.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.auth.CodeFormat
import xyz.aresius.audiobooks.core.design.Spacing

/** How a typed or pasted invite/link code stands: [count] normalised characters, [ok] when it is a complete valid code. */
data class CodeProgress(val count: Int, val ok: Boolean, val badCharacters: Boolean) {
    companion object {
        fun of(text: String): CodeProgress {
            val normalized = CodeFormat.normalize(text)
            return CodeProgress(normalized.length, CodeFormat.isValid(normalized), normalized.any { it !in CodeFormat.ALPHABET })
        }
    }
}

/**
 * Field for an invite or link code: forgiving about case, spaces and dashes, with a live character count, **Paste** and **Scan QR code**
 * (Google's code scanner; when it is missing the view model says so and the user pastes or types).
 */
@Composable
fun CodeField(
    value: String,
    label: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    onPaste: (String) -> Unit,
    onScan: (ScanResult) -> Unit,
) {
    val progress = CodeProgress.of(value)
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val wrong = value.isNotBlank() && (progress.badCharacters || progress.count > CodeFormat.LENGTH)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        isError = wrong,
        supportingText = {
            when {
                wrong -> Text(stringResource(R.string.input_code_invalid))
                progress.ok -> Text(stringResource(R.string.code_input_ok))
                value.isNotBlank() -> Text(stringResource(R.string.code_input_count, progress.count))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
        enabled = enabled,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedButton(
            onClick = {
                scope.launch { clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let(onPaste) }
            },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.code_paste), color = MaterialTheme.colorScheme.onBackground) }
        OutlinedButton(
            onClick = { scope.launch { onScan(scanQr(context)) } },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.code_scan), color = MaterialTheme.colorScheme.onBackground) }
    }
}
