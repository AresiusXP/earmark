package xyz.aresius.audiobooks.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import xyz.aresius.audiobooks.BuildConfig
import xyz.aresius.audiobooks.core.auth.CredentialManagerGoogleTokenSource
import xyz.aresius.audiobooks.core.auth.DebugGoogleTokenSource
import xyz.aresius.audiobooks.core.auth.GoogleTokenSource
import xyz.aresius.audiobooks.core.auth.RoutingGoogleTokenSource

/** Fake Google sign-ins of debug builds (empty in release builds). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DebugGoogleSources

@Module
@InstallIn(SingletonComponent::class)
object GoogleModule {
    @Provides @Singleton
    fun googleTokenSource(@DebugGoogleSources debug: @JvmSuppressWildcards List<DebugGoogleTokenSource>): GoogleTokenSource =
        RoutingGoogleTokenSource(CredentialManagerGoogleTokenSource(BuildConfig.GOOGLE_SERVER_CLIENT_ID), debug)
}
