package xyz.aresius.audiobooks.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import xyz.aresius.audiobooks.core.torrent.AndroidDeviceStateMonitor
import xyz.aresius.audiobooks.core.torrent.AndroidServiceLauncher
import xyz.aresius.audiobooks.core.torrent.DeviceStateMonitor
import xyz.aresius.audiobooks.core.torrent.DeviceTorrentSettings
import xyz.aresius.audiobooks.core.torrent.EngineConfig
import xyz.aresius.audiobooks.core.torrent.LibTorrentEngine
import xyz.aresius.audiobooks.core.torrent.ServiceLauncher
import xyz.aresius.audiobooks.core.torrent.TorrentEngine
import xyz.aresius.audiobooks.core.torrent.TorrentRuntime
import xyz.aresius.audiobooks.core.torrent.TorrentSettingsSource
import xyz.aresius.audiobooks.core.torrent.TorrentSettingsStore
import xyz.aresius.audiobooks.core.torrent.TorrentStore

/** The on-phone BitTorrent engine (core/torrent). core/download injects [TorrentEngine]; the service injects [TorrentRuntime] (same instance). */
@Module
@InstallIn(SingletonComponent::class)
abstract class TorrentBindingsModule {
    @Binds abstract fun engine(impl: LibTorrentEngine): TorrentEngine

    @Binds abstract fun runtime(impl: LibTorrentEngine): TorrentRuntime

    @Binds abstract fun settingsStore(impl: DeviceTorrentSettings): TorrentSettingsStore

    @Binds abstract fun settingsSource(impl: DeviceTorrentSettings): TorrentSettingsSource

    @Binds abstract fun deviceStateMonitor(impl: AndroidDeviceStateMonitor): DeviceStateMonitor

    @Binds abstract fun serviceLauncher(impl: AndroidServiceLauncher): ServiceLauncher
}

@Module
@InstallIn(SingletonComponent::class)
object TorrentProvidesModule {
    /** Fast-resume data and the DHT state live in the no-backup dir: they are device-specific and must not be restored elsewhere. */
    @Provides @Singleton
    fun torrentStore(@ApplicationContext context: Context): TorrentStore = TorrentStore(File(context.noBackupFilesDir, "torrent"))

    @Provides
    fun engineConfig(): EngineConfig = EngineConfig()
}
