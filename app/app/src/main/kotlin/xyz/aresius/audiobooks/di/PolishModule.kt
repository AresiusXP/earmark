package xyz.aresius.audiobooks.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import xyz.aresius.audiobooks.core.data.AndroidNetworkEvents
import xyz.aresius.audiobooks.core.data.NetworkEvents

@Module
@InstallIn(SingletonComponent::class)
abstract class PolishBindingsModule {
    @Binds @Singleton
    abstract fun networkEvents(impl: AndroidNetworkEvents): NetworkEvents
}
