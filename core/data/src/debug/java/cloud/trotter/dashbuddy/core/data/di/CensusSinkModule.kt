package cloud.trotter.dashbuddy.core.data.di

import cloud.trotter.dashbuddy.domain.capture.CensusEnvelopeSink
import cloud.trotter.dashbuddy.domain.capture.CensusSink
import cloud.trotter.dashbuddy.core.data.census.HttpCensusSink
import cloud.trotter.dashbuddy.core.data.census.PersistentCensusEnvelopeSink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Debug uploader is opt-in through developer settings. Release binds NoOpCensusSink and stays inert by construction. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CensusSinkModule {

    @Binds
    @Singleton
    abstract fun bindCensusEnvelopeSink(impl: PersistentCensusEnvelopeSink): CensusEnvelopeSink

    @Binds
    @Singleton
    abstract fun bindCensusSink(impl: HttpCensusSink): CensusSink
}
