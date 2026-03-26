package cloud.trotter.dashbuddy.core.location.di

import cloud.trotter.dashbuddy.core.location.FusedLocationDataSource
import cloud.trotter.dashbuddy.core.location.LocationDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationModule {

    @Binds
    @Singleton
    abstract fun bindLocationDataSource(
        impl: FusedLocationDataSource
    ): LocationDataSource
}