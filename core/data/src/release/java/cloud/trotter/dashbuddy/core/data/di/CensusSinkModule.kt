package cloud.trotter.dashbuddy.core.data.di

import cloud.trotter.dashbuddy.domain.capture.CensusEnvelopeSink
import cloud.trotter.dashbuddy.domain.capture.CensusSink
import cloud.trotter.dashbuddy.core.data.capture.NoOpCensusSink
import cloud.trotter.dashbuddy.domain.capture.NoOpCensusEnvelopeSink
import dagger.Provides
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Release stays inert by construction: only the debug source set binds the HTTP uploader. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CensusSinkModule {
    companion object {
        @Provides
        @Singleton
        fun provideCensusEnvelopeSink(): CensusEnvelopeSink = NoOpCensusEnvelopeSink
    }


    @Binds
    @Singleton
    abstract fun bindCensusSink(impl: NoOpCensusSink): CensusSink
}
