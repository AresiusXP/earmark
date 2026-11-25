package xyz.aresius.audiobooks.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.download.DownloadedLocalFiles
import xyz.aresius.audiobooks.core.download.DownloadsDao
import xyz.aresius.audiobooks.core.download.LocalFiles
import xyz.aresius.audiobooks.core.download.StorageManager

/** The real [LocalFiles]: complete files of the downloads database. core/torrent binds `TorrentEngine` in its own module ([DownloadsBindings] picks it up). */
@Module
@InstallIn(SingletonComponent::class)
object LocalFilesModule {
    @Provides @Singleton
    fun localFiles(dao: DownloadsDao, sessions: SessionStore, storage: StorageManager, @DownloadScope scope: CoroutineScope): LocalFiles =
        DownloadedLocalFiles(dao, sessions, storage, scope)
}
