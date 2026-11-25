package xyz.aresius.audiobooks.core.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hands a verified APK to the system installer. */
interface ApkInstaller {
    /** `PackageManager.canRequestPackageInstalls()`: "Install unknown apps" is on for this app. */
    fun canInstall(): Boolean

    /** Starts the install session; the result arrives later through [InstallResults]. */
    suspend fun install(apk: File): InstallStart
}

sealed interface InstallStart {
    data class Session(val id: Int) : InstallStart
    data class Failed(val error: UpdateError) : InstallStart
}

/**
 * Where the manifest-registered receiver reports `PackageInstaller` statuses. Only the session this app opened
 * ([expect]) is accepted: a status for any other session id (another app's, a stale one) is dropped.
 */
@Singleton
class InstallResults @Inject constructor() {
    @Volatile var listener: ((InstallOutcome) -> Unit)? = null
    @Volatile private var active: Int = NONE

    fun expect(sessionId: Int) {
        active = sessionId
    }

    fun clear() {
        active = NONE
    }

    fun accepts(sessionId: Int): Boolean = active != NONE && sessionId == active

    fun report(sessionId: Int, outcome: InstallOutcome) {
        if (!accepts(sessionId)) return
        if (outcome != InstallOutcome.NeedsUserAction) clear()
        listener?.invoke(outcome)
    }

    private companion object {
        const val NONE = Int.MIN_VALUE
    }
}

@Singleton
class PackageApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val results: InstallResults,
) : ApkInstaller {
    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override suspend fun install(apk: File): InstallStart = withContext(Dispatchers.IO) {
        try {
            InstallStart.Session(commitSession(apk))
        } catch (e: IOException) {
            InstallStart.Failed(UpdateError.InstallFailed(e.javaClass.simpleName))
        } catch (e: SecurityException) {
            InstallStart.Failed(UpdateError.InstallFailed(e.javaClass.simpleName))
        } catch (e: IllegalStateException) {
            InstallStart.Failed(UpdateError.InstallFailed(e.javaClass.simpleName))
        }
    }

    private fun commitSession(apk: File): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        results.expect(id)
        val session = installer.openSession(id)
        try {
            session.openWrite("audiobooks.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            session.commit(statusSender(id))
            return id
        } catch (e: Throwable) {
            results.clear()
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }

    /**
     * FLAG_MUTABLE is required: the system fills in EXTRA_STATUS, EXTRA_STATUS_MESSAGE and EXTRA_INTENT (the confirmation screen) on
     * the PendingIntent's base intent. The intent is explicit (our own receiver, not exported), so no other app can hijack it.
     */
    private fun statusSender(sessionId: Int) = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    ).intentSender

    companion object {
        fun unknownSourcesSettings(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    }
}
