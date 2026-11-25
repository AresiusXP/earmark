package xyz.aresius.audiobooks.core.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * A session together with the identity of the sign-in it belongs to. [id] changes on every [SessionStore.save] (sign-in, join, link,
 * user switch) and every [SessionStore.clear]; a token refresh keeps it. A request, or a refresh, that started under one [id] must never
 * act on (retry with, persist into, clear) a session with another one.
 */
data class SessionHandle(val session: Session, val id: Long)

/** Encrypted storage of the [Session]. All reads after the first are served from memory. */
interface SessionStore {
    /** Emits the current session (null when signed out) and every change. */
    val session: Flow<Session?>

    suspend fun load(): Session?
    suspend fun save(session: Session)
    suspend fun clear()

    /** In-memory value; blocks on the first call only (used from OkHttp threads). */
    fun snapshot(): Session?

    /** Like [snapshot], with the identity of the sign-in (read atomically with it). */
    fun handle(): SessionHandle?

    /** Stores refreshed tokens, but only when [expected] is still exactly the current session; false (nothing written) otherwise. Keeps the id. */
    suspend fun replaceIfCurrent(expected: SessionHandle, updated: Session): Boolean

    /** Signs out, but only when [expected] is still the current sign-in; false (nothing done) otherwise. */
    suspend fun clearIfCurrent(expected: SessionHandle): Boolean

    fun saveBlocking(session: Session) = runBlocking { save(session) }
    fun clearBlocking() = runBlocking { clear() }
    fun replaceIfCurrentBlocking(expected: SessionHandle, updated: Session): Boolean = runBlocking { replaceIfCurrent(expected, updated) }
    fun clearIfCurrentBlocking(expected: SessionHandle): Boolean = runBlocking { clearIfCurrent(expected) }
}

/** Symmetric encryption of the session blob (Tink AEAD on device, a fake in JVM tests). */
interface TokenCipher {
    fun encrypt(plain: String): String
    fun decrypt(encrypted: String): String
}

/** Persistence of an opaque string (DataStore on device, in memory in tests). */
interface BlobStorage {
    suspend fun read(): String?
    suspend fun write(value: String?)
}

class EncryptedSessionStore(
    private val cipher: TokenCipher,
    private val storage: BlobStorage,
) : SessionStore {
    private val state = MutableStateFlow<SessionHandle?>(null)
    private val mutex = Mutex()
    private var lastId = 0L
    @Volatile private var loaded = false

    override val session: Flow<Session?> = flow {
        ensureLoaded()
        emitAll(state.map { it?.session })
    }

    override suspend fun load(): Session? {
        ensureLoaded()
        return state.value?.session
    }

    override fun snapshot(): Session? = handle()?.session

    override fun handle(): SessionHandle? {
        if (!loaded) runBlocking { ensureLoaded() }
        return state.value
    }

    override suspend fun save(session: Session) {
        ensureLoaded()
        mutex.withLock {
            write(session)
            state.value = SessionHandle(session, ++lastId)
        }
    }

    override suspend fun clear() {
        ensureLoaded()
        mutex.withLock {
            storage.write(null)
            lastId++
            state.value = null
        }
    }

    override suspend fun replaceIfCurrent(expected: SessionHandle, updated: Session): Boolean {
        ensureLoaded()
        return mutex.withLock {
            if (state.value != expected) return@withLock false
            write(updated)
            state.value = SessionHandle(updated, expected.id)
            true
        }
    }

    override suspend fun clearIfCurrent(expected: SessionHandle): Boolean {
        ensureLoaded()
        return mutex.withLock {
            if (state.value != expected) return@withLock false
            storage.write(null)
            lastId++
            state.value = null
            true
        }
    }

    private suspend fun write(session: Session) = storage.write(cipher.encrypt(Json.encodeToString(Session.serializer(), session)))

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            state.value = runCatching {
                storage.read()?.let { Json.decodeFromString(Session.serializer(), cipher.decrypt(it)) }
            }.getOrNull()?.let { SessionHandle(it, ++lastId) } // undecryptable blob (keystore wiped, corrupt): behave as signed out
            loaded = true
        }
    }
}

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore("audiobooks_session")

class DataStoreBlobStorage(private val dataStore: DataStore<Preferences>) : BlobStorage {
    private val key = stringPreferencesKey("session_v1")
    override suspend fun read(): String? = dataStore.data.map { it[key] }.first()
    override suspend fun write(value: String?) {
        dataStore.edit { if (value == null) it.remove(key) else it[key] = value }
    }
}

/** AES-256-GCM through Tink; the keyset is wrapped by a master key in the Android Keystore. */
class TinkTokenCipher(context: Context, private val aad: ByteArray = AAD) : TokenCipher {
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "audiobooks_keyset", "audiobooks_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://audiobooks_master_key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override fun encrypt(plain: String): String =
        Base64.getEncoder().encodeToString(aead.encrypt(plain.toByteArray(), aad))

    override fun decrypt(encrypted: String): String =
        String(aead.decrypt(Base64.getDecoder().decode(encrypted), aad))

    companion object {
        private val AAD = "audiobooks-session-v1".toByteArray()
    }
}

@Singleton
class DeviceSessionStore @Inject constructor(@ApplicationContext context: Context) :
    SessionStore by EncryptedSessionStore(TinkTokenCipher(context), DataStoreBlobStorage(context.sessionDataStore))
