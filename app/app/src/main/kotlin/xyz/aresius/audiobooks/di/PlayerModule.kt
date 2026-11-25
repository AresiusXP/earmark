package xyz.aresius.audiobooks.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import xyz.aresius.audiobooks.core.network.ApiProvider
import xyz.aresius.audiobooks.core.player.ApiProgressRemote
import xyz.aresius.audiobooks.core.player.DevicePlayerPreferences
import xyz.aresius.audiobooks.core.player.PlayerDao
import xyz.aresius.audiobooks.core.player.PlayerDb
import xyz.aresius.audiobooks.core.player.PlayerPreferences
import xyz.aresius.audiobooks.core.player.ProgressRemote
import xyz.aresius.audiobooks.core.player.ProgressSync
import xyz.aresius.audiobooks.core.player.SyncScheduler
import xyz.aresius.audiobooks.core.player.WorkSyncScheduler

@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerBindingsModule {
    @Binds @Singleton
    abstract fun playerPreferences(impl: DevicePlayerPreferences): PlayerPreferences
}

/** The player's own Room database (progress, learned durations, bookmarks): separate from `audiobooks.db` so the two never share a schema. */
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {
    @Provides @Singleton
    fun playerDb(@ApplicationContext context: Context): PlayerDb = Room.databaseBuilder(context, PlayerDb::class.java, "audiobooks_player.db").build()

    @Provides fun playerDao(db: PlayerDb): PlayerDao = db.dao()

    @Provides @Singleton
    fun progressRemote(provider: ApiProvider): ProgressRemote = ApiProgressRemote(provider)

    @Provides @Singleton
    fun syncScheduler(@ApplicationContext context: Context): SyncScheduler = WorkSyncScheduler(context)

    @Provides @Singleton
    fun progressSync(dao: PlayerDao, remote: ProgressRemote, scheduler: SyncScheduler): ProgressSync = ProgressSync(dao, remote, scheduler)
}
