package cloud.trotter.log.gig.core.data.di

import cloud.trotter.log.gig.core.data.census.PersistentCensusEnvelopeSink
import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The test build's trusted-envelope path (capture sharing, off by default). Release binds the no-op until #1633. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CensusEnvelopeSinkModule {

    @Binds
    @Singleton
    abstract fun bindCensusEnvelopeSink(impl: PersistentCensusEnvelopeSink): CensusEnvelopeSink
}
