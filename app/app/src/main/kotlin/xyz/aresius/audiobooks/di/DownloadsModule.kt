package xyz.aresius.audiobooks.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import java.io.File
import java.util.Optional
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import xyz.aresius.audiobooks.core.data.LibraryDao
import xyz.aresius.audiobooks.core.data.ReleaseRepository
import xyz.aresius.audiobooks.core.data.SessionWipeHook
import xyz.aresius.audiobooks.core.download.AndroidNetworkStatus
import xyz.aresius.audiobooks.core.download.DebridSource
import xyz.aresius.audiobooks.core.download.DeviceDownloadSettings
import xyz.aresius.audiobooks.core.download.DownloadChoice
import xyz.aresius.audiobooks.core.download.DownloadMaintenance
import xyz.aresius.audiobooks.core.download.DownloadNotifications
import xyz.aresius.audiobooks.core.download.DownloadRunner
import xyz.aresius.audiobooks.core.download.DownloadScheduler
import xyz.aresius.audiobooks.core.download.DownloadSettings
import xyz.aresius.audiobooks.core.download.DownloadSources
import xyz.aresius.audiobooks.core.download.DownloadWorkerDeps
import xyz.aresius.audiobooks.core.download.DownloadsDao
import xyz.aresius.audiobooks.core.download.DownloadsDb
import xyz.aresius.audiobooks.core.download.DownloadsRepository
import xyz.aresius.audiobooks.core.data.finishedAtMs
import xyz.aresius.audiobooks.core.download.FinishedBooks
import xyz.aresius.audiobooks.core.download.FreeSpace
import xyz.aresius.audiobooks.core.download.HttpFileFetcher
import xyz.aresius.audiobooks.core.download.NetworkStatus
import xyz.aresius.audiobooks.core.download.NoPlayingBook
import xyz.aresius.audiobooks.core.download.PlayingBook
import xyz.aresius.audiobooks.core.download.ProgressBus
import xyz.aresius.audiobooks.core.download.StatFsFreeSpace
import xyz.aresius.audiobooks.core.download.StorageManager
import xyz.aresius.audiobooks.core.download.TorrentSource
import xyz.aresius.audiobooks.core.download.WorkManagerScheduler
import xyz.aresius.audiobooks.core.network.HttpClientFactory
import xyz.aresius.audiobooks.core.torrent.TorrentEngine

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadScope

/** Hilt wiring of core/download. `TorrentEngine` and `PlayingBook` are optional bindings: the torrent and player modules just `@Binds` theirs. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadsBindings {
    @Binds @Singleton abstract fun settings(impl: DeviceDownloadSettings): DownloadSettings
    @Binds @Singleton abstract fun network(impl: AndroidNetworkStatus): NetworkStatus
    @Binds @Singleton abstract fun scheduler(impl: WorkManagerScheduler): DownloadScheduler
    @Binds abstract fun workerDeps(impl: HiltDownloadWorkerDeps): DownloadWorkerDeps
    @Binds @IntoSet abstract fun wipeHook(impl: DownloadsWipeHook): SessionWipeHook

    /** The torrent agent binds [TorrentEngine] in its own module; until then this is empty and the torrent source reports "unavailable". */
    @BindsOptionalOf abstract fun optionalTorrentEngine(): TorrentEngine

    /** The player binds [PlayingBook] in its own module; until then nobody is "playing" and the storage rules just never protect a book. */
    @BindsOptionalOf abstract fun optionalPlayingBook(): PlayingBook

    /** Other features register their own wipe hooks; declared here so the set exists even when nobody else does. */
    @Multibinds abstract fun wipeHooks(): Set<SessionWipeHook>
}

class DownloadsWipeHook @Inject constructor(private val downloads: dagger.Lazy<DownloadsRepository>) : SessionWipeHook {
    override suspend fun wipe() = downloads.get().wipe()
}

@Singleton
class HiltDownloadWorkerDeps @Inject constructor(
    override val runner: DownloadRunner,
    override val bus: ProgressBus,
    override val notifications: DownloadNotifications,
    override val scheduler: DownloadScheduler,
    override val maintenance: DownloadMaintenance,
) : DownloadWorkerDeps

@Module
@InstallIn(SingletonComponent::class)
object DownloadsProvides {
    @Provides @Singleton
    fun downloadsDb(@ApplicationContext context: Context): DownloadsDb =
        Room.databaseBuilder(context, DownloadsDb::class.java, "downloads.db")
            // Rows only describe files on disk; if the schema changes without a migration, the orphan cleanup removes the files.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun downloadsDao(db: DownloadsDb): DownloadsDao = db.downloadsDao()

    @Provides @Singleton
    fun booksDir(@ApplicationContext context: Context): File = File(context.filesDir, "books")

    @Provides @Singleton
    fun freeSpace(dir: File): FreeSpace = StatFsFreeSpace(dir)

    @Provides @Singleton @DownloadScope
    fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides @Singleton
    fun finishedBooks(libraryDao: LibraryDao): FinishedBooks = FinishedBooks { userId -> libraryDao.finishedAtMs(userId) }

    /** The credential-free redirect-following client ([HttpClientFactory.cdn]): this client only ever talks to a provider's CDN. No whole-call timeout (files are big). */
    @Provides @Singleton @DownloadClient
    fun downloadClient(clients: HttpClientFactory): OkHttpClient = clients.cdn().newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun storageManager(
        dao: DownloadsDao, root: File, settings: DownloadSettings, finished: FinishedBooks, playing: Optional<PlayingBook>, free: FreeSpace,
        torrent: Optional<TorrentEngine>,
    ): StorageManager = StorageManager(
        dao = dao, root = root, settings = settings, finished = finished, playing = playing.orElse(NoPlayingBook), free = free,
        onBookRemoved = { book -> if (book.source == DownloadChoice.TORRENT_SOURCE && torrent.isPresent) runCatching { torrent.get().remove(book.releaseHash, deleteFiles = true) } },
    )

    @Provides @Singleton
    fun downloadSources(releases: ReleaseRepository, @DownloadClient client: OkHttpClient, torrent: Optional<TorrentEngine>): DownloadSources {
        val debrid = DebridSource(releases, HttpFileFetcher(client))
        val torrentSource = TorrentSource({ torrent.orElse(null) }, releases)
        return DownloadSources { choice -> if (choice is DownloadChoice.Torrent) torrentSource else debrid }
    }
}
