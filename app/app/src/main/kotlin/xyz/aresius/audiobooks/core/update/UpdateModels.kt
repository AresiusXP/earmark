package xyz.aresius.audiobooks.core.update

import java.io.File
import xyz.aresius.audiobooks.core.network.AppUpdateDto

/** A validated, newer build offered by the server. Only built through [AppUpdateInfo.from]. */
data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val size: Long,
    val sha256: String,
) {
    /** Safe file name, a 64-hex digest and a size within [UpdatePolicy.MAX_APK_BYTES]: anything else is not installable. */
    fun isWellFormed(): Boolean =
        isSafeVersion(versionName) && sha256.length == 64 && sha256.all { it in HEX } && size in 1..UpdatePolicy.MAX_APK_BYTES

    companion object {
        private const val HEX = "0123456789abcdef"

        /** The version name ends up in a file name: a short token of `[0-9A-Za-z._-]` that starts with a letter or digit. */
        fun isSafeVersion(name: String): Boolean =
            name.length in 1..32 && name[0].isLetterOrDigit() && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }

        /** The offer when [dto] says an update is available, is newer than [installedCode] and is well-formed; null otherwise. */
        fun from(dto: AppUpdateDto, installedCode: Long): AppUpdateInfo? {
            if (!dto.available || dto.versionCode <= installedCode) return null
            return AppUpdateInfo(dto.version, dto.versionCode, dto.size, dto.sha256.lowercase()).takeIf { it.isWellFormed() }
        }
    }
}

/** When to check and when to hide the banner. Pure so it is easy to test. */
object UpdatePolicy {
    /** One tiny GET, so a foreground visit may repeat it every 10 minutes. */
    const val CHECK_INTERVAL_MS: Long = 10L * 60 * 1000

    /** A forced check always runs. Otherwise one is due after [CHECK_INTERVAL_MS], or when the clock went backwards. */
    fun isCheckDue(lastCheckMs: Long, nowMs: Long, force: Boolean = false): Boolean =
        force || lastCheckMs <= 0 || nowMs < lastCheckMs || nowMs - lastCheckMs >= CHECK_INTERVAL_MS

    /** "Later" hides the banner for that version and every older one; a newer version is offered again. */
    fun isDismissed(dismissedVersionCode: Long, update: AppUpdateInfo): Boolean = dismissedVersionCode >= update.versionCode

    /** The largest APK the updater will fetch. A bigger advertised size is an invalid offer. */
    const val MAX_APK_BYTES: Long = 300L * 1024 * 1024
}

sealed interface UpdateError {
    data object NotAvailable : UpdateError
    data object Network : UpdateError
    data object Corrupt : UpdateError
    data class Server(val code: Int) : UpdateError
    data object SignatureMismatch : UpdateError
    data object InstallStorage : UpdateError
    data class InstallFailed(val detail: String?) : UpdateError
}

sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data class Downloading(val bytes: Long, val total: Long) : UpdatePhase

    /** Downloaded and verified; tapping Install hands it to the system installer. */
    data object Ready : UpdatePhase

    /** "Install unknown apps" is off for this app: the UI explains and opens the system setting. */
    data class NeedsInstallPermission(val settingsOpened: Boolean = false) : UpdatePhase
    data object Installing : UpdatePhase
    data class Failed(val error: UpdateError) : UpdatePhase
}

data class UpdateState(
    val installedVersionName: String = "",
    val installedVersionCode: Long = 0,
    val available: AppUpdateInfo? = null,
    val dismissed: Boolean = false,
    val checking: Boolean = false,
    val lastCheckedAtMs: Long = 0,
    val phase: UpdatePhase = UpdatePhase.Idle,
) {
    val showBanner: Boolean get() = available != null && !dismissed
}

sealed interface DownloadOutcome {
    data class Success(val file: File) : DownloadOutcome
    data class Failure(val error: UpdateError) : DownloadOutcome
}

val UpdatePhase.isBusy: Boolean get() = this is UpdatePhase.Downloading || this is UpdatePhase.Installing
