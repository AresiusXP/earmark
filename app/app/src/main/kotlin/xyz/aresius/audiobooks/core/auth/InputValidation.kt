package xyz.aresius.audiobooks.core.auth

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Why an input was refused; the UI maps each to a string resource. */
enum class InputProblem {
    UrlEmpty,
    UrlInvalid,
    UrlNeedsHttps,
    SecretEmpty,
    NameEmpty,
    NameTooLong,
    DeviceNameEmpty,
    DeviceNameTooLong,
}

sealed interface UrlValidation {
    /** [url] is normalised: scheme + host[:port][/path], no trailing slash. */
    data class Valid(val url: String) : UrlValidation
    data class Invalid(val problem: InputProblem) : UrlValidation
}

object InputValidation {
    const val MAX_DISPLAY_NAME = 40
    const val MAX_DEVICE_NAME = 80

    /** Hosts the manifest's network security config allows plain http for (release builds); debug builds allow every host. */
    val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun isLocalHost(host: String): Boolean = host in LOCAL_HOSTS

    /** https is required, except for [LOCAL_HOSTS] and, when [allowCleartextAnyHost] (debug builds), every host. */
    fun validateUrl(input: String, allowCleartextAnyHost: Boolean): UrlValidation {
        val raw = input.trim()
        if (raw.isEmpty()) return UrlValidation.Invalid(InputProblem.UrlEmpty)
        val withScheme = if ("://" in raw) raw else "https://$raw"
        val url = withScheme.toHttpUrlOrNull() ?: return UrlValidation.Invalid(InputProblem.UrlInvalid)
        if (url.scheme != "https" && !allowCleartextAnyHost && !isLocalHost(url.host)) {
            return UrlValidation.Invalid(InputProblem.UrlNeedsHttps)
        }
        val path = url.encodedPath.trimEnd('/')
        val defaultPort = if (url.scheme == "https") 443 else 80
        val port = if (url.port == defaultPort) "" else ":${url.port}"
        val host = if (':' in url.host) "[${url.host}]" else url.host
        return UrlValidation.Valid("${url.scheme}://$host$port$path")
    }

    fun validateDisplayName(name: String): InputProblem? = when {
        name.isBlank() -> InputProblem.NameEmpty
        name.trim().length > MAX_DISPLAY_NAME -> InputProblem.NameTooLong
        else -> null
    }

    fun validateDeviceName(name: String): InputProblem? = when {
        name.isBlank() -> InputProblem.DeviceNameEmpty
        name.trim().length > MAX_DEVICE_NAME -> InputProblem.DeviceNameTooLong
        else -> null
    }
}
