package xyz.aresius.audiobooks.feature.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.ErrorCode

/** The string for an error without parameters, or null when the message needs the server's text / a number (see [message]). */
@StringRes
fun ApiError.messageRes(): Int? = when (this) {
    ApiError.Network -> R.string.error_network
    ApiError.NotSignedIn -> R.string.error_auth
    is ApiError.Unknown -> R.string.error_unknown
    is ApiError.Http -> when (code) {
        ErrorCode.Unauthorized -> R.string.error_auth
        ErrorCode.Forbidden -> R.string.error_forbidden
        ErrorCode.NotFound -> R.string.error_not_found
        ErrorCode.Conflict -> R.string.error_conflict
        ErrorCode.NoDebridKey -> R.string.error_no_debrid_key
        ErrorCode.BadDebridKey -> R.string.error_bad_debrid_key
        ErrorCode.DebridUnavailable -> R.string.error_debrid_unavailable
        ErrorCode.LimitReached -> R.string.error_limit_reached
        ErrorCode.GoogleDisabled -> R.string.google_error_disabled
        ErrorCode.RateLimited, ErrorCode.GoogleNotLinked, ErrorCode.InvalidRequest, ErrorCode.UpstreamFailed, ErrorCode.Other -> null
    }
}

@Composable
fun ApiError.message(): String {
    val res = messageRes()
    if (res != null) return stringResource(res)
    val http = this as? ApiError.Http ?: return stringResource(R.string.error_unknown)
    return when {
        http.code == ErrorCode.RateLimited && http.retryAfterSec != null -> stringResource(R.string.error_rate_limited_in, http.retryAfterSec)
        http.code == ErrorCode.RateLimited -> stringResource(R.string.error_rate_limited)
        // The server's own words for a request it refused (shown as is: a debrid provider's message is the most useful one).
        http.message != null -> http.message
        else -> stringResource(R.string.error_server, http.status)
    }
}
