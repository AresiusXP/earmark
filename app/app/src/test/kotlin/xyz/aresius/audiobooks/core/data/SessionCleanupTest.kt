package xyz.aresius.audiobooks.core.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.Me
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.MemoryLibraryDao
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.testSession

class SessionCleanupTest {
    private val server = FakeServer()
    private val app = TestApp(server)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val cleanup = SessionCleanup(app.sessions, app.cache, app.libraryDao, app.me, app.prefs)

    @AfterTest fun tearDown() {
        scope.cancel()
        server.close()
    }

    private suspend fun fill() {
        app.me.set(AudiobooksJson.decodeFromString(Me.serializer(), Samples.ME_ADMIN))
        server.onJson("GET", "/v1/me/library", """{"entries":[${Samples.libraryEntry("B000000001")}]}""")
        app.library.refresh()
        app.prefs.setOnboardingDone(true)
    }

    private fun untilTrue(block: () -> Boolean) {
        val end = System.nanoTime() + 3_000_000_000
        while (System.nanoTime() < end && !block()) Thread.sleep(10)
        assertTrue(block())
    }

    @Test fun signingOutWipesCacheLibraryProfileAndTheOnboardingFlag() = runBlocking<Unit> {
        fill()
        cleanup.start(scope)
        assertEquals(1, app.memLibrary.all.size) // a signed-in start keeps the data
        app.sessions.clear()
        untilTrue { app.memLibrary.all.isEmpty() }
        assertTrue(app.cache.rows.isEmpty())
        assertNull(app.me.me.value)
        assertEquals(false, app.prefs.doneNow)
    }

    @Test fun anotherUserSigningInWipesTheOldOnesData() = runBlocking<Unit> {
        fill()
        cleanup.start(scope)
        app.sessions.save(testSession(server.baseUrl, userId = "u2"))
        untilTrue { app.memLibrary.all.isEmpty() }
    }

    @Test fun refreshingTokensOfTheSameUserKeepsEverything() = runBlocking<Unit> {
        fill()
        cleanup.start(scope)
        app.sessions.save(app.sessions.load()!!.copy(accessToken = "rotated"))
        Thread.sleep(100)
        assertEquals(1, app.memLibrary.all.size)
        assertEquals(true, app.prefs.doneNow)
    }
}
