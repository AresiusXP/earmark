package xyz.aresius.audiobooks.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import xyz.aresius.audiobooks.BuildConfig
import xyz.aresius.audiobooks.core.network.ApiResult
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.MemoryPrefs
import xyz.aresius.audiobooks.testutil.TestApp

class AppUpdateRepositoryTest {
    @Test fun `without a server url the check is up to date and nothing is requested`() = runBlocking<Unit> {
        val server = FakeServer()
        try {
            val app = TestApp(server, session = false)
            val repo = AppUpdateRepositoryImpl(app.provider, MemoryPrefs(url = ""))
            val r = repo.check(installedVersionCode = 1)
            assertEquals(ApiResult.Success(null), r)
            assertEquals(0, server.requests.size)
        } finally {
            server.close()
        }
    }

    @Test fun `the Google web client ID has a default and looks like one`() {
        assertEquals(true, BuildConfig.GOOGLE_SERVER_CLIENT_ID.endsWith(".apps.googleusercontent.com"))
    }
}
