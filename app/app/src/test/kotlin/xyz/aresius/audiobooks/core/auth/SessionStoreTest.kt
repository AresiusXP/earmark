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
}
