package xyz.aresius.audiobooks.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.Interceptor
import xyz.aresius.audiobooks.core.network.DebugInterceptors
import xyz.aresius.audiobooks.debug.FakeBackend

/** Debug builds: the in-process FakeBackend answers `http://demo.audiobooks.test` (every other host goes to the network as usual). */
@Module
@InstallIn(SingletonComponent::class)
object DebugToolsModule {
    @Provides @Singleton
    fun fakeBackend(): FakeBackend = FakeBackend()

    @Provides @DebugInterceptors
    fun debugInterceptors(backend: FakeBackend): List<Interceptor> = listOf(backend)
}
