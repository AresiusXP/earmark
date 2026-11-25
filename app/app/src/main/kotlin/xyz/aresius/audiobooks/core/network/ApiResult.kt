package xyz.aresius.audiobooks.core.network

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** Machine-readable `error.code` values of the contract; anything else is [Other]. */
enum class ErrorCode(val wire: String) {
    InvalidRequest("invalid_request"),
    Unauthorized("unauthorized"),
    Forbidden("forbidden"),
    NotFound("not_found"),
    Conflict("conflict"),
    RateLimited("rate_limited"),
    NoDebridKey("no_debrid_key"),
    BadDebridKey("bad_debrid_key"),
    DebridUnavailable("debrid_unavailable"),
    LimitReached("limit_reached"),
    UpstreamFailed("upstream_failed"),
    GoogleDisabled("google_disabled"),
    GoogleNotLinked("google_not_linked"),
    Other(""),
    ;

    companion object {
        fun fromWire(value: String?): ErrorCode = entries.firstOrNull { it.wire.isNotEmpty() && it.wire == value } ?: Other
    }
}

sealed interface ApiError {
    /** No connectivity, timeout, TLS or DNS failure. */
    data object Network : ApiError

    data object NotSignedIn : ApiError

    /**
     * The server answered with an error. [code] comes from the body `{"error":{"code","message"}}` and falls back to the HTTP status when the
     * body is missing or not ours (a proxy page); [rawCode] is the body's code text as sent; [message] is the server's text, shown as is.
     */
    data class Http(
        val status: Int,
        val code: ErrorCode,
        val rawCode: String? = null,
        val message: String? = null,
        val retryAfterSec: Int? = null,
    ) : ApiError

    data class Unknown(val message: String? = null) : ApiError
}

val ApiError.isUnauthorized: Boolean get() = this is ApiError.Http && (status == 401 || code == ErrorCode.Unauthorized)
val ApiError.isNotFound: Boolean get() = this is ApiError.Http && (status == 404 || code == ErrorCode.NotFound)
val ApiError.isRateLimited: Boolean get() = this is ApiError.Http && (status == 429 || code == ErrorCode.RateLimited)
val ApiError.isTransient: Boolean get() = this is ApiError.Http && status in setOf(502, 503, 504)

/** Result of a repository/API call. [Success.stale] is true when cached data was served because the network failed. */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T, val stale: Boolean = false) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

val <T> ApiResult<T>.valueOrNull: T? get() = (this as? ApiResult.Success)?.value
val ApiResult<*>.errorOrNull: ApiError? get() = (this as? ApiResult.Failure)?.error

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value), stale)
    is ApiResult.Failure -> this
}

class NotSignedInException : IOException("Not signed in")

/**
 * Runs [block] and turns every failure into [ApiResult.Failure]. [timeoutMs] is a last-resort bound on the whole call: whatever
 * stalls, the caller gets [ApiError.Network] instead of a spinner that never ends.
 */
suspend fun <T> safeApiCall(timeoutMs: Long = NetworkTimeouts.API_CALL_MS, block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(if (timeoutMs > 0) withTimeout(timeoutMs) { block() } else block())
} catch (e: TimeoutCancellationException) {
    ApiResult.Failure(ApiError.Network)
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    ApiResult.Failure(e.toApiError())
}

fun Throwable.toApiError(): ApiError = when (this) {
    is NotSignedInException -> ApiError.NotSignedIn
    is HttpException -> {
        val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
        apiErrorFrom(code(), body, response()?.headers()?.get("Retry-After")?.trim()?.toIntOrNull())
    }
    is IOException -> ApiError.Network
    is SerializationException -> ApiError.Unknown("Unexpected server response")
    else -> ApiError.Unknown(message)
}

/** The [ApiError] of an HTTP failure: status [status], the JSON error [body] (if any) and the `Retry-After` header seconds. */
fun apiErrorFrom(status: Int, body: String?, retryAfterHeader: Int?): ApiError.Http {
    val detail = body?.takeIf { it.isNotBlank() }?.let {
        runCatching { AudiobooksJson.decodeFromString(ApiErrorBody.serializer(), it).error }.getOrNull()
    }
    val code = detail?.code?.let(ErrorCode::fromWire)?.takeIf { it != ErrorCode.Other } ?: codeForStatus(status)
    return ApiError.Http(
        status = status,
        code = code,
        rawCode = detail?.code?.takeIf { it.isNotBlank() },
        message = detail?.message?.takeIf { it.isNotBlank() },
        retryAfterSec = detail?.retryAfterS ?: retryAfterHeader,
    )
}

private fun codeForStatus(status: Int): ErrorCode = when (status) {
    400 -> ErrorCode.InvalidRequest
    401 -> ErrorCode.Unauthorized
    403 -> ErrorCode.Forbidden
    404 -> ErrorCode.NotFound
    409 -> ErrorCode.Conflict
    429 -> ErrorCode.RateLimited
    else -> ErrorCode.Other
}
