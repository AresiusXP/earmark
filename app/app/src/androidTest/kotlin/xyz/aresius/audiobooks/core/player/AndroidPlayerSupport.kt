package xyz.aresius.audiobooks.core.player

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import xyz.aresius.audiobooks.core.auth.BlobStorage
import xyz.aresius.audiobooks.core.auth.EncryptedSessionStore
import xyz.aresius.audiobooks.core.auth.Session
import xyz.aresius.audiobooks.core.auth.TokenCipher

/** Runs [block] on the main thread (the player's application looper) and returns its result. */
fun <T> onMain(block: () -> T): T {
    val result = AtomicReference<Result<T>>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(runCatching(block)) }
    return result.get().getOrThrow()
}

/** Polls [condition] on the main thread until it holds or [timeoutMs] passes. */
fun awaitMain(timeoutMs: Long = 10_000, message: String = "condition", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (onMain(condition)) return
        Thread.sleep(50)
    }
    throw AssertionError("$message not met within $timeoutMs ms")
}

/** A silent 16-bit mono 8 kHz WAV of [millis]: small, exact duration, plays on every device. */
fun writeWav(file: File, millis: Int): File {
    val samples = 8 * millis
    val data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
    data.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
    data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
    data.put("data".toByteArray()).putInt(samples * 2)
    file.writeBytes(data.array())
    return file
}

fun memoryStore(session: Session? = null) = EncryptedSessionStore(
    object : TokenCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(encrypted: String) = encrypted.reversed()
    },
    object : BlobStorage {
        private var value: String? = null
        override suspend fun read() = value
        override suspend fun write(value: String?) { this.value = value }
    },
).also { store -> if (session != null) kotlinx.coroutines.runBlocking { store.save(session) } }


/** [actual] within [tolerance] of [expected] (playback positions are never exact). */
fun assertNear(expected: Long, actual: Long, tolerance: Long = 200) {
    if (kotlin.math.abs(expected - actual) > tolerance) throw AssertionError("expected $expected +-$tolerance but was $actual")
}
