package cloud.trotter.dashbuddy.core.data.di

import cloud.trotter.dashbuddy.core.data.capture.CaptureBus
import cloud.trotter.dashbuddy.core.data.capture.NoOpCaptureBus
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Release variant: capture persistence is OFF — third-party screen content is
 * never written to disk (privacy pledge, #346). Debug binds
 * [cloud.trotter.dashbuddy.core.data.capture.DiskCaptureBus] instead.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CaptureBusModule {

    @Binds
    @Singleton
    abstract fun bindCaptureBus(impl: NoOpCaptureBus): CaptureBus
}
