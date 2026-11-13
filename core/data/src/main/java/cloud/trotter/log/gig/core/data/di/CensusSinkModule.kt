package cloud.trotter.log.gig.core.data.di

import cloud.trotter.log.gig.core.data.census.HttpCensusSink
import cloud.trotter.log.gig.domain.capture.CensusSink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * #1620: every build binds the census sink; the census consent decides whether it queues anything. The capture bus and
 * the envelope sink stay per build (no screen text leaves a Play or sideload phone).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CensusSinkModule {

    @Binds
    @Singleton
    abstract fun bindCensusSink(impl: HttpCensusSink): CensusSink
}
