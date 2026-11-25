package xyz.aresius.audiobooks.testutil

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import xyz.aresius.audiobooks.core.auth.BlobStorage
import xyz.aresius.audiobooks.core.auth.EncryptedSessionStore
import xyz.aresius.audiobooks.core.auth.Session
import xyz.aresius.audiobooks.core.auth.TokenCipher
import xyz.aresius.audiobooks.core.data.AppPreferences
import xyz.aresius.audiobooks.core.data.CacheDao
import xyz.aresius.audiobooks.core.data.CacheEntryEntity
import xyz.aresius.audiobooks.core.data.LibraryDao
import xyz.aresius.audiobooks.core.data.LibraryEntryEntity

/** Not encryption: tests only need a transform that differs from the plain text. */
class ReversingCipher : TokenCipher {
    override fun encrypt(plain: String) = "enc:" + plain.reversed()
    override fun decrypt(encrypted: String): String {
        require(encrypted.startsWith("enc:")) { "not encrypted" }
        return encrypted.removePrefix("enc:").reversed()
    }
}

class MemoryBlob(initial: String? = null) : BlobStorage {
    private val value = AtomicReference(initial)
    override suspend fun read(): String? = value.get()
    override suspend fun write(value: String?) = this.value.set(value)
    val raw: String? get() = value.get()
}

fun memorySessionStore(initial: Session? = null) = EncryptedSessionStore(ReversingCipher(), MemoryBlob())
    .also { store -> if (initial != null) kotlinx.coroutines.runBlocking { store.save(initial) } }

fun testSession(serverUrl: String, userId: String = "u1") =
    Session(serverUrl, accessToken = "access-1", refreshToken = "refresh-1", expiresAtMs = 0, deviceName = "Pixel", userId = userId)

class MemoryPrefs(url: String = "https://earmark.aresius.xyz", done: Boolean = false) : AppPreferences {
    private val url = MutableStateFlow(url)
    private val done = MutableStateFlow(done)
    private val dismissed = MutableStateFlow(0L)
    override val serverUrl: Flow<String> = this.url
    override suspend fun setServerUrl(url: String) { this.url.value = url }
    override val onboardingDone: Flow<Boolean> = this.done
    override suspend fun setOnboardingDone(done: Boolean) { this.done.value = done }
    override val dismissedUpdateVersionCode: Flow<Long> = dismissed
    override suspend fun setDismissedUpdateVersionCode(code: Long) { dismissed.value = code }
    private val dynamic = MutableStateFlow(false)
    override val dynamicColor: Flow<Boolean> = dynamic
    override suspend fun setDynamicColor(enabled: Boolean) { dynamic.value = enabled }
    val doneNow: Boolean get() = done.value
}

class MemoryCacheDao : CacheDao {
    val rows = HashMap<String, CacheEntryEntity>()
    override suspend fun get(key: String) = rows[key]
    override suspend fun put(entry: CacheEntryEntity) { rows[entry.key] = entry }
    override suspend fun clear() = rows.clear()
}

class MemoryLibraryDao : LibraryDao() {
    private val rows = MutableStateFlow<List<LibraryEntryEntity>>(emptyList())
    val all: List<LibraryEntryEntity> get() = rows.value
    override fun observeAll(userId: String): Flow<List<LibraryEntryEntity>> =
        rows.map { list -> list.filter { it.userId == userId }.sortedByDescending { it.updatedAt } }
    override fun observe(userId: String, asin: String): Flow<LibraryEntryEntity?> =
        rows.map { list -> list.firstOrNull { it.userId == userId && it.asin == asin } }
    override suspend fun upsert(entry: LibraryEntryEntity) { rows.value = rows.value.filterNot { it.userId == entry.userId && it.asin == entry.asin } + entry }
    override suspend fun upsertAll(entries: List<LibraryEntryEntity>) = entries.forEach { upsert(it) }
    override suspend fun delete(userId: String, asin: String) { rows.value = rows.value.filterNot { it.userId == userId && it.asin == asin } }
    override suspend fun deleteAll(userId: String) { rows.value = rows.value.filterNot { it.userId == userId } }
    override suspend fun clear() { rows.value = emptyList() }
}
