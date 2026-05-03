package cloud.trotter.dashbuddy.di

import cloud.trotter.dashbuddy.core.data.capture.CaptureBus
import cloud.trotter.dashbuddy.core.data.capture.DiskCaptureBus
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PipelineModule {

    // =========================================================================
    // CAPTURE
    // =========================================================================

    @Binds @Singleton
    abstract fun bindCaptureBus(impl: DiskCaptureBus): CaptureBus
}
