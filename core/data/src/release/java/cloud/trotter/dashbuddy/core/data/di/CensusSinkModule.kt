package cloud.trotter.dashbuddy.core.data.di

import cloud.trotter.dashbuddy.domain.capture.CensusSink
import cloud.trotter.dashbuddy.core.data.capture.NoOpCensusSink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Release stays inert by construction: only the debug source set binds the HTTP uploader. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CensusSinkModule {

    @Binds
    @Singleton
    abstract fun bindCensusSink(impl: NoOpCensusSink): CensusSink
}
