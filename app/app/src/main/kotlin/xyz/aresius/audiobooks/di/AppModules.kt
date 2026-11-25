package xyz.aresius.audiobooks.di

import android.content.Context
import android.os.Build
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import xyz.aresius.audiobooks.BuildConfig
import xyz.aresius.audiobooks.core.auth.BuildInfo
import xyz.aresius.audiobooks.core.auth.DeviceSessionStore
import xyz.aresius.audiobooks.core.auth.SessionStore
import xyz.aresius.audiobooks.core.data.AppPreferences
import xyz.aresius.audiobooks.core.data.AppUpdateRepository
import xyz.aresius.audiobooks.core.data.AppUpdateRepositoryImpl
import xyz.aresius.audiobooks.core.data.AudiobooksDb
import xyz.aresius.audiobooks.core.data.CacheDao
import xyz.aresius.audiobooks.core.data.CatalogRepository
import xyz.aresius.audiobooks.core.data.CatalogRepositoryImpl
import xyz.aresius.audiobooks.core.data.DeviceAppPreferences
import xyz.aresius.audiobooks.core.data.LibraryDao
import xyz.aresius.audiobooks.core.update.AndroidUpdateEnvironment
import xyz.aresius.audiobooks.core.update.ApkInstaller
import xyz.aresius.audiobooks.core.update.PackageApkInstaller
import xyz.aresius.audiobooks.core.update.ServerUpdateDownloads
import xyz.aresius.audiobooks.core.update.UpdateDownloads
import xyz.aresius.audiobooks.core.update.UpdateEnvironment
import xyz.aresius.audiobooks.core.update.UpdateFiles
import xyz.aresius.audiobooks.core.update.updatesDir

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds @Singleton
    abstract fun sessionStore(impl: DeviceSessionStore): SessionStore

    @Binds @Singleton
    abstract fun appPreferences(impl: DeviceAppPreferences): AppPreferences

    @Binds @Singleton
    abstract fun catalogRepository(impl: CatalogRepositoryImpl): CatalogRepository

    @Binds @Singleton
    abstract fun appUpdateRepository(impl: AppUpdateRepositoryImpl): AppUpdateRepository

    @Binds @Singleton
    abstract fun updateDownloads(impl: ServerUpdateDownloads): UpdateDownloads

    @Binds @Singleton
    abstract fun apkInstaller(impl: PackageApkInstaller): ApkInstaller

    @Binds @Singleton
    abstract fun updateEnvironment(impl: AndroidUpdateEnvironment): UpdateEnvironment
}

@Module
@InstallIn(SingletonComponent::class)
object ProvidesModule {
    @Provides @Singleton
    fun buildInfo(): BuildInfo = BuildInfo(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        debugTools = BuildConfig.DEBUG_TOOLS,
        deviceModel = Build.MODEL.orEmpty(),
    )

    @Provides @Singleton
    fun db(@ApplicationContext context: Context): AudiobooksDb =
        Room.databaseBuilder(context, AudiobooksDb::class.java, "audiobooks.db")
            // Everything in it is a copy of the server's data: safe to rebuild.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun cacheDao(db: AudiobooksDb): CacheDao = db.cacheDao()

    @Provides fun libraryDao(db: AudiobooksDb): LibraryDao = db.libraryDao()

    @Provides @Singleton
    fun updateFiles(@ApplicationContext context: Context): UpdateFiles = UpdateFiles(updatesDir(context))
}
