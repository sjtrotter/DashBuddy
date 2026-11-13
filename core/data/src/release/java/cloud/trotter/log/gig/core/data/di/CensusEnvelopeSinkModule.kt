package cloud.trotter.log.gig.core.data.di

import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import cloud.trotter.log.gig.domain.capture.NoOpCensusEnvelopeSink
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The Play and sideload builds send no screen text: no capture envelopes, so no envelope sink (ADR-0011 §12). */
@Module
@InstallIn(SingletonComponent::class)
object CensusEnvelopeSinkModule {
    @Provides
    @Singleton
    fun provideCensusEnvelopeSink(): CensusEnvelopeSink = NoOpCensusEnvelopeSink
}
