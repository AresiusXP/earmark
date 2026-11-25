package xyz.aresius.audiobooks.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import xyz.aresius.audiobooks.core.auth.DebugGoogleTokenSource
import xyz.aresius.audiobooks.debug.FakeGoogleTokenSource

/** Debug builds: a fake Google for the demo backend (see [FakeGoogleTokenSource]). */
@Module
@InstallIn(SingletonComponent::class)
object DebugGoogleModule {
    @Provides @DebugGoogleSources
    fun debugGoogleSources(): List<DebugGoogleTokenSource> = listOf(FakeGoogleTokenSource())
}
