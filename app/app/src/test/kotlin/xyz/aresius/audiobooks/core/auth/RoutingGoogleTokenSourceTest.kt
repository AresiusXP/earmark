package xyz.aresius.audiobooks.core.auth

import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RoutingGoogleTokenSourceTest {
    private class Source(val name: String, val available: Boolean = true) : DebugGoogleTokenSource {
        override fun isAvailable(context: Context) = available
        override fun handles(nonce: String) = nonce.startsWith("demo-")
        override suspend fun request(context: Context, nonce: String): GoogleTokenResult = GoogleTokenResult.Failed(name)
    }

    private class Real(val available: Boolean) : GoogleTokenSource {
        override fun isAvailable(context: Context) = available
        override suspend fun request(context: Context, nonce: String): GoogleTokenResult = GoogleTokenResult.Failed("real")
    }

    // The Context is never touched by these sources: a null-free stand-in is not needed on the JVM stubs.
    private val context: Context = object : android.content.ContextWrapper(null) {}

    @Test fun theDemoBackendsNoncesGoToTheDebugFakeAndEverythingElseToCredentialManager() = runBlocking<Unit> {
        val router = RoutingGoogleTokenSource(Real(false), listOf(Source("fake")))
        assertEquals(GoogleTokenResult.Failed("fake"), router.request(context, "demo-nonce-1"))
        assertEquals(GoogleTokenResult.Failed("real"), router.request(context, "nonce-from-a-real-server"))
    }

    @Test fun releaseBuildsHaveNoFakeAndFollowThePlayServicesState() {
        assertTrue(RoutingGoogleTokenSource(Real(true), emptyList()).isAvailable(context))
        assertFalse(RoutingGoogleTokenSource(Real(false), emptyList()).isAvailable(context))
        assertTrue(RoutingGoogleTokenSource(Real(false), listOf(Source("fake"))).isAvailable(context), "debug: the fake is available")
    }
}
