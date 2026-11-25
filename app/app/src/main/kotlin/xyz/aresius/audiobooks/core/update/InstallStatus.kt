package xyz.aresius.audiobooks.core.update

/** What a `PackageInstaller` status broadcast means for the updater. */
sealed interface InstallOutcome {
    /** The system wants the user to confirm: launch `Intent.EXTRA_INTENT`. */
    data object NeedsUserAction : InstallOutcome
    data object Success : InstallOutcome
    /** The user dismissed the system dialog: the APK is still cached, so Install can be tapped again. */
    data object Cancelled : InstallOutcome
    data class Failed(val error: UpdateError) : InstallOutcome
}

/** Pure mapping of `PackageInstaller.EXTRA_STATUS` / `EXTRA_STATUS_MESSAGE` (numbers from `PackageInstaller.STATUS_*`). */
object InstallStatus {
    const val PENDING_USER_ACTION = -1
    const val SUCCESS = 0
    const val FAILURE = 1
    const val FAILURE_BLOCKED = 2
    const val FAILURE_ABORTED = 3
    const val FAILURE_INVALID = 4
    const val FAILURE_CONFLICT = 5
    const val FAILURE_STORAGE = 6
    const val FAILURE_INCOMPATIBLE = 7

    fun map(status: Int, message: String?): InstallOutcome {
        val signature = message?.let { "UPDATE_INCOMPATIBLE" in it || "signatures do not match" in it || "signature" in it.lowercase() } == true
        return when {
            status == PENDING_USER_ACTION -> InstallOutcome.NeedsUserAction
            status == SUCCESS -> InstallOutcome.Success
            status == FAILURE_ABORTED -> InstallOutcome.Cancelled
            status == FAILURE_CONFLICT || signature -> InstallOutcome.Failed(UpdateError.SignatureMismatch)
            status == FAILURE_STORAGE -> InstallOutcome.Failed(UpdateError.InstallStorage)
            status == FAILURE_INVALID -> InstallOutcome.Failed(UpdateError.InstallFailed("the package is invalid"))
            status == FAILURE_BLOCKED -> InstallOutcome.Failed(UpdateError.InstallFailed("blocked by the device"))
            status == FAILURE_INCOMPATIBLE -> InstallOutcome.Failed(UpdateError.InstallFailed("not compatible with this device"))
            else -> InstallOutcome.Failed(UpdateError.InstallFailed(message?.takeIf { it.isNotBlank() }))
        }
    }
}
