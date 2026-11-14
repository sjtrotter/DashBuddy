package cloud.trotter.log.gig.core.data.di

import cloud.trotter.log.gig.core.data.wipe.ActivityManagerStorageClearer
import cloud.trotter.log.gig.core.data.wipe.AppStorageClearer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the on-phone wipe's clear (#1579) to Android's own Clear storage. */
@Module
@InstallIn(SingletonComponent::class)
abstract class WipeBindModule {

    @Binds
    abstract fun bindAppStorageClearer(impl: ActivityManagerStorageClearer): AppStorageClearer
}
