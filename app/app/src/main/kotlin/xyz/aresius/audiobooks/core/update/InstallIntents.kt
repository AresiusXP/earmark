package xyz.aresius.audiobooks.core.update

/** Pure checks on what the `PackageInstaller` status broadcast asks the app to do. */
object InstallIntents {
    private val CONFIRM_ACTIONS = setOf(
        "android.content.pm.action.CONFIRM_INSTALL",
        "android.content.pm.action.CONFIRM_PERMISSIONS",
    )

    // FLAG_GRANT_READ_URI_PERMISSION | WRITE | PERSISTABLE | PREFIX
    private const val GRANT_FLAGS = 0x1 or 0x2 or 0x40 or 0x80

    /**
     * The intent in `Intent.EXTRA_INTENT` is only launched when it is the system's confirmation screen: a confirm
     * action, a target package that is not this app and is a system app (the platform package installer), and no
     * URI-permission grants.
     */
    fun isTrustedConfirmIntent(
        action: String?,
        targetPackage: String?,
        flags: Int,
        ownPackage: String,
        isSystemApp: (String) -> Boolean,
    ): Boolean =
        action in CONFIRM_ACTIONS &&
            targetPackage != null && targetPackage != ownPackage &&
            flags and GRANT_FLAGS == 0 &&
            isSystemApp(targetPackage)
}
