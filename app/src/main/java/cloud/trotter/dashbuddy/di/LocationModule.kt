package cloud.trotter.dashbuddy.di

import cloud.trotter.dashbuddy.data.location.FusedLocationDataSource
import cloud.trotter.dashbuddy.data.location.LocationDataSource
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