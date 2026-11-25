package xyz.aresius.audiobooks.core.auth

import kotlinx.serialization.Serializable

/** This device's credentials. [serverUrl] has no trailing slash. */
@Serializable
data class Session(
    val serverUrl: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val deviceName: String,
    val userId: String,
) {
    // Keep tokens out of logs.
    override fun toString() = "Session(serverUrl=$serverUrl, deviceName=$deviceName)"
}
