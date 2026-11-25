package xyz.aresius.audiobooks.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import xyz.aresius.audiobooks.core.download.LocalFiles
import xyz.aresius.audiobooks.core.download.NoLocalFiles

/** Placeholder bindings; core/download replaces [LocalFiles], core/torrent will add the TorrentEngine binding in its own module. */
@Module
@InstallIn(SingletonComponent::class)
object LocalFilesModule {
    @Provides @Singleton
    fun localFiles(): LocalFiles = NoLocalFiles
}
