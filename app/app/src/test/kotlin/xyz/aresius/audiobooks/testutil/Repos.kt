package xyz.aresius.audiobooks.testutil

import xyz.aresius.audiobooks.core.auth.AuthRepository
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.data.AdminRepository
import xyz.aresius.audiobooks.core.data.CatalogRepositoryImpl
import xyz.aresius.audiobooks.core.data.DebridRepository
import xyz.aresius.audiobooks.core.data.LibraryDao
import xyz.aresius.audiobooks.core.data.LibraryRepository
import xyz.aresius.audiobooks.core.data.MeRepository
import xyz.aresius.audiobooks.core.data.ReleaseRepository
import xyz.aresius.audiobooks.core.network.ApiProvider
import xyz.aresius.audiobooks.core.network.HttpClientFactory
import xyz.aresius.audiobooks.core.network.NetworkTimeouts
import xyz.aresius.audiobooks.core.network.SignedOutEvents

/** Repositories wired to a [FakeServer] (plain Retrofit, no auth layer: that is covered by ApiClientTest). */
class TestApp(val server: FakeServer, session: Boolean = true) {
    val sessions: SessionStore = memorySessionStore(if (session) testSession(server.baseUrl) else null)
    val prefs = MemoryPrefs()
    val cache = MemoryCacheDao()
    val memLibrary = MemoryLibraryDao()
    val libraryDao: LibraryDao = memLibrary
    val signedOut = SignedOutEvents()
    var now = 1_700_000_000_000L
    val me = MeRepository({ server.api }, cache) { now }
    val debrid = DebridRepository { server.api }
    val admin = AdminRepository { server.api }
    val releases = ReleaseRepository { server.api }
    val catalog = CatalogRepositoryImpl({ server.api }, cache, { now })
    val library = LibraryRepository({ server.api }, libraryDao, sessions) { now }
    private val factory = HttpClientFactory(sessions, signedOut, NetworkTimeouts(), emptyList())
    val provider = ApiProvider(sessions, factory)
    fun auth(allowCleartext: Boolean = true) = AuthRepository(provider, sessions, signedOut, prefs, me, allowCleartext) { now }
}
