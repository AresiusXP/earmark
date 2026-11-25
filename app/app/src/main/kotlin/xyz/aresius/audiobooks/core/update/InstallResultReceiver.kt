package xyz.aresius.audiobooks.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives the `PackageInstaller` session status (not exported; reached through an explicit PendingIntent).
 * Statuses of sessions this app did not open are ignored, and the confirmation screen is only launched when it
 * belongs to the system's package installer.
 */
@AndroidEntryPoint
class InstallResultReceiver : BroadcastReceiver() {
    @Inject lateinit var results: InstallResults

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (!results.accepts(sessionId)) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val outcome = InstallStatus.map(status, message)
        if (outcome == InstallOutcome.NeedsUserAction) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            if (confirm == null || !isTrusted(context, confirm)) {
                results.report(sessionId, InstallOutcome.Failed(UpdateError.InstallFailed("unexpected confirmation screen")))
                return
            }
            context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        results.report(sessionId, outcome)
    }

    private fun isTrusted(context: Context, confirm: Intent): Boolean {
        val pm = context.packageManager
        val target = confirm.component?.packageName ?: confirm.`package` ?: pm.resolveActivity(confirm, 0)?.activityInfo?.packageName
        return InstallIntents.isTrustedConfirmIntent(confirm.action, target, confirm.flags, context.packageName) { pkg ->
            try {
                pm.getApplicationInfo(pkg, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}
