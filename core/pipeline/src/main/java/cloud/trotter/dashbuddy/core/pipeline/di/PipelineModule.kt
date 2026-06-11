package cloud.trotter.dashbuddy.core.pipeline.di

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.core.pipeline.ReplayMetadataProviderImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PipelineModule {

    // CaptureBus is bound in :core:data's per-variant CaptureBusModule
    // (debug → DiskCaptureBus, release → NoOpCaptureBus, #346).

    @Binds @Singleton
    abstract fun bindReplayMetadataProvider(impl: ReplayMetadataProviderImpl): ReplayMetadataProvider
}
