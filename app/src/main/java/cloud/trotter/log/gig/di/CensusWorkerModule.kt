package cloud.trotter.log.gig.di

import cloud.trotter.log.gig.domain.census.CensusUploadScheduler
import cloud.trotter.log.gig.worker.CensusWorkScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CensusWorkerModule {
    @Binds
    @Singleton
    abstract fun bindCensusScheduler(impl: CensusWorkScheduler): CensusUploadScheduler
}
