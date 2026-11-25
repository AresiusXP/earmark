package xyz.aresius.audiobooks.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import xyz.aresius.audiobooks.core.network.DebugInterceptors

/** Release builds carry no debug tooling (the debug source set provides the in-process FakeBackend). */
@Module
@InstallIn(SingletonComponent::class)
object DebugToolsModule {
    @Provides @DebugInterceptors
    fun debugInterceptors(): List<Interceptor> = emptyList()
}
