package xyz.aresius.audiobooks.core.auth

/** What the build says about itself; injected so JVM tests can pick any values. [debugTools] is true in debug builds only. */
data class BuildInfo(
    val versionName: String,
    val versionCode: Long,
    val debugTools: Boolean,
    /** `Build.MODEL`: the suggested device name on the sign-in screen. */
    val deviceModel: String = "",
)
