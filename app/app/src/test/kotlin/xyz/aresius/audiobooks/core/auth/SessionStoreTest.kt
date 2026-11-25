package xyz.aresius.audiobooks.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import xyz.aresius.audiobooks.testutil.MemoryBlob
import xyz.aresius.audiobooks.testutil.ReversingCipher
import xyz.aresius.audiobooks.testutil.testSession

class SessionStoreTest {
    @Test fun savesEncryptedAndReloads() = runBlocking<Unit> {
        val blob = MemoryBlob()
        val store = EncryptedSessionStore(ReversingCipher(), blob)
        assertNull(store.load())
        store.save(testSession("https://x.example"))
        assertFalse(blob.raw!!.contains("access-1"), "the token must not be stored in clear text")
        val again = EncryptedSessionStore(ReversingCipher(), blob)
        assertEquals("access-1", again.load()?.accessToken)
        assertEquals("u1", again.snapshot()?.userId)
        assertEquals("u1", again.session.first()?.userId)
    }

    @Test fun clearForgetsEverything() = runBlocking<Unit> {
        val blob = MemoryBlob()
        val store = EncryptedSessionStore(ReversingCipher(), blob)
        store.save(testSession("https://x.example"))
        store.clear()
        assertNull(store.load())
        assertNull(blob.raw)
    }

    @Test fun anUndecryptableBlobMeansSignedOut() = runBlocking<Unit> {
        val store = EncryptedSessionStore(ReversingCipher(), MemoryBlob("garbage"))
        assertNull(store.load())
    }

    @Test fun toStringNeverShowsTokens() {
        val text = testSession("https://x.example").toString()
        assertTrue("access-1" !in text && "refresh-1" !in text)
    }

    @Test fun aRefreshKeepsTheSignInIdentityButEveryOtherWriteChangesIt() = runBlocking<Unit> {
        val store = EncryptedSessionStore(ReversingCipher(), MemoryBlob())
        store.save(testSession("https://x.example"))
        val first = store.handle()!!
        assertTrue(store.replaceIfCurrent(first, first.session.copy(accessToken = "a2", refreshToken = "r2")))
        val refreshed = store.handle()!!
        assertEquals(first.id, refreshed.id)
        assertEquals("r2", refreshed.session.refreshToken)
        store.save(testSession("https://x.example")) // a new sign-in, even with the same user
        assertTrue(store.handle()!!.id != first.id)
    }

    @Test fun aStaleHandleCanNeitherReplaceNorClear() = runBlocking<Unit> {
        val blob = MemoryBlob()
        val store = EncryptedSessionStore(ReversingCipher(), blob)
        store.save(testSession("https://x.example", "u1"))
        val old = store.handle()!!
        store.save(testSession("https://x.example", "u2"))
        assertFalse(store.replaceIfCurrent(old, old.session.copy(accessToken = "late")))
        assertFalse(store.clearIfCurrent(old))
        assertEquals("u2", store.load()?.userId)
        assertEquals("access-1", store.load()?.accessToken)
        val current = store.handle()!!
        store.clear()
        assertFalse(store.replaceIfCurrent(current, current.session.copy(accessToken = "late")), "a sign-out is not undone")
        assertNull(store.load())
        assertNull(blob.raw)
        assertFalse(store.clearIfCurrent(current))
    }

    @Test fun twoRefreshesFromTheSameTokensOnlyOneWins() = runBlocking<Unit> {
        val store = EncryptedSessionStore(ReversingCipher(), MemoryBlob())
        store.save(testSession("https://x.example"))
        val h = store.handle()!!
        assertTrue(store.replaceIfCurrent(h, h.session.copy(refreshToken = "r2")))
        assertFalse(store.replaceIfCurrent(h, h.session.copy(refreshToken = "r3")))
        assertEquals("r2", store.load()?.refreshToken)
    }
}
