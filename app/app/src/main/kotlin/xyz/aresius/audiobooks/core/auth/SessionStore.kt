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

/** Encrypted storage of the [Session]. All reads after the first are served from memory. */
interface SessionStore {
    /** Emits the current session (null when signed out) and every change. */
    val session: Flow<Session?>

    suspend fun load(): Session?
    suspend fun save(session: Session)
    suspend fun clear()

    /** In-memory value; blocks on the first call only (used from OkHttp threads). */
    fun snapshot(): Session?
    fun saveBlocking(session: Session) = runBlocking { save(session) }
    fun clearBlocking() = runBlocking { clear() }
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
    private val state = MutableStateFlow<Session?>(null)
    private val mutex = Mutex()
    @Volatile private var loaded = false

    override val session: Flow<Session?> = flow {
        ensureLoaded()
        emitAll(state)
    }

    override suspend fun load(): Session? {
        ensureLoaded()
        return state.value
    }

    override fun snapshot(): Session? {
        if (!loaded) runBlocking { ensureLoaded() }
        return state.value
    }

    override suspend fun save(session: Session) {
        ensureLoaded()
        mutex.withLock {
            storage.write(cipher.encrypt(Json.encodeToString(Session.serializer(), session)))
            state.value = session
        }
    }

    override suspend fun clear() {
        ensureLoaded()
        mutex.withLock {
            storage.write(null)
            state.value = null
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            state.value = runCatching {
                storage.read()?.let { Json.decodeFromString(Session.serializer(), cipher.decrypt(it)) }
            }.getOrNull() // undecryptable blob (keystore wiped, corrupt): behave as signed out
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
