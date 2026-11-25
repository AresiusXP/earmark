package xyz.aresius.audiobooks.core.network

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class ApiErrorTest {
    @Test fun parsesTheContractErrorBody() {
        val e = apiErrorFrom(403, """{"error":{"code":"no_debrid_key","message":"Add a key first"}}""", null)
        assertEquals(403, e.status)
        assertEquals(ErrorCode.NoDebridKey, e.code)
        assertEquals("no_debrid_key", e.rawCode)
        assertEquals("Add a key first", e.message)
    }

    @Test fun everyDocumentedCodeMapsToATypedCode() {
        val codes = mapOf(
            "invalid_request" to ErrorCode.InvalidRequest, "unauthorized" to ErrorCode.Unauthorized, "forbidden" to ErrorCode.Forbidden,
            "not_found" to ErrorCode.NotFound, "conflict" to ErrorCode.Conflict, "rate_limited" to ErrorCode.RateLimited,
            "no_debrid_key" to ErrorCode.NoDebridKey, "bad_debrid_key" to ErrorCode.BadDebridKey, "debrid_unavailable" to ErrorCode.DebridUnavailable,
            "limit_reached" to ErrorCode.LimitReached, "upstream_failed" to ErrorCode.UpstreamFailed,
        )
        codes.forEach { (wire, typed) -> assertEquals(typed, apiErrorFrom(400, """{"error":{"code":"$wire"}}""", null).code, wire) }
    }

    @Test fun middlewareUnauthorizedBodyHasNoMessage() {
        val e = apiErrorFrom(401, """{"error":{"code":"unauthorized"}}""", null)
        assertEquals(ErrorCode.Unauthorized, e.code)
        assertNull(e.message)
        assertTrue(e.isUnauthorized)
    }

    @Test fun unknownCodeKeepsTheRawTextAndFallsBackToTheStatus() {
        val e = apiErrorFrom(409, """{"error":{"code":"brand_new_code","message":"x"}}""", null)
        assertEquals(ErrorCode.Conflict, e.code)
        assertEquals("brand_new_code", e.rawCode)
        assertEquals(ErrorCode.Other, apiErrorFrom(500, """{"error":{"code":"brand_new_code"}}""", null).code)
    }

    @Test fun aProxyPageOrEmptyBodyStillYieldsATypedError() {
        assertEquals(ErrorCode.Other, apiErrorFrom(502, "<html>Bad Gateway</html>", null).code)
        assertEquals(502, apiErrorFrom(502, "", null).status)
        assertEquals(ErrorCode.NotFound, apiErrorFrom(404, null, null).code)
        assertTrue(apiErrorFrom(503, null, null).isTransient)
    }

    @Test fun retryAfterComesFromTheBodyFirstThenTheHeader() {
        assertEquals(30, apiErrorFrom(429, """{"error":{"code":"rate_limited","retry_after_s":30}}""", 5).retryAfterSec)
        assertEquals(5, apiErrorFrom(429, """{"error":{"code":"rate_limited"}}""", 5).retryAfterSec)
        assertTrue(apiErrorFrom(429, null, null).isRateLimited)
    }

    @Test fun throwablesMapToApiErrors() = runBlocking<Unit> {
        val http = HttpException(Response.error<Any>(404, """{"error":{"code":"not_found","message":"no book"}}""".toResponseBody("application/json".toMediaType())))
        val mapped = http.toApiError()
        assertIs<ApiError.Http>(mapped)
        assertTrue(mapped.isNotFound)
        assertEquals(ApiError.Network, IOException("down").toApiError())
        assertEquals(ApiError.NotSignedIn, NotSignedInException().toApiError())
        assertIs<ApiError.Unknown>(kotlinx.serialization.SerializationException("bad").toApiError())
        val failed = safeApiCall<Unit> { throw IOException("x") }
        assertEquals(ApiError.Network, failed.errorOrNull)
        assertEquals(5, safeApiCall { 5 }.valueOrNull)
    }
}
