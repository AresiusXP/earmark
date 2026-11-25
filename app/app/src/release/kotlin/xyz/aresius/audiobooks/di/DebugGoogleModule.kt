package xyz.aresius.audiobooks.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import xyz.aresius.audiobooks.core.auth.DebugGoogleTokenSource

/** Release builds have no fake Google. */
@Module
@InstallIn(SingletonComponent::class)
object DebugGoogleModule {
    @Provides @DebugGoogleSources
    fun debugGoogleSources(): List<DebugGoogleTokenSource> = emptyList()
}
