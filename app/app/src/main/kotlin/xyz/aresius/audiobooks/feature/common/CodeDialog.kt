package xyz.aresius.audiobooks.feature.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.auth.CodeFormat

/**
 * Shows a one-time code (invite or link code) for the other phone: a QR code to scan, the code in blocks of five for reading it out or
 * typing it, **Copy**, **Share** and the time left. The code is not shown again once the dialog closes, so the copy is marked sensitive
 * (Android hides it from the clipboard preview) where the platform supports that. Copy and QR carry the plain 26 characters; the
 * share text has dashes (it survives chat apps), and the sign-in screen accepts every form.
 */
@Composable
fun CodeDialog(title: String, message: String, code: String, expiresAt: String?, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plain = CodeFormat.normalize(code)
    val expiry = remember(expiresAt) { expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() } }
    val now by produceState(Instant.now()) {
        while (true) {
            delay(1_000)
            value = Instant.now()
        }
    }
    val remaining = expiry?.let { Duration.between(now, it) }
    val expired = remaining != null && remaining.isNegative
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (!expired) QrImage(plain, stringResource(R.string.code_qr_description), Modifier.size(200.dp))
                GroupedCode(plain)
                when {
                    expired -> Text(stringResource(R.string.code_expired), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    remaining != null && remaining < Duration.ofDays(1) ->
                        Text(stringResource(R.string.code_expires_in, formatRemaining(remaining)), style = MaterialTheme.typography.bodyMedium)
                    expiry != null -> Text(stringResource(R.string.code_expires, formatInstant(expiresAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.code_scan_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch { clipboard.setClipEntry(ClipEntry(sensitiveClip(plain))) }
                            copied = true
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(if (copied) R.string.code_copied else R.string.code_copy), color = MaterialTheme.colorScheme.onSurface) }
                    OutlinedButton(onClick = { shareCode(context, title, CodeFormat.grouped(plain, "-")) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.code_share), color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
    )
}

/** The code in blocks of five, three blocks to a line, in a monospace face. */
@Composable
private fun GroupedCode(plain: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        plain.chunked(CodeFormat.GROUP).chunked(3).forEach { line ->
            Text(
                line.joinToString("  "),
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** "14:32", or "1:05:09" from an hour up. */
fun formatRemaining(remaining: Duration): String {
    val total = remaining.seconds.coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun shareCode(context: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(send, title))
    } catch (_: ActivityNotFoundException) {
        // no app can take text: the copy button still works
    }
}

private fun sensitiveClip(code: String): ClipData {
    val clip = ClipData.newPlainText("code", code)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    return clip
}
