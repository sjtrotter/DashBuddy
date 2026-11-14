package cloud.trotter.log.gig.core.data.di

import cloud.trotter.log.gig.core.data.census.contributor.CensusContributor
import cloud.trotter.log.gig.domain.census.contributor.ContributorGate
import cloud.trotter.log.gig.domain.census.contributor.PendingContributorReviews
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** #1625: the contributor gate, and the reviews waiting on the phone. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ContributorModule {
    @Binds
    abstract fun bindContributorGate(impl: CensusContributor): ContributorGate

    companion object {
        /** Nothing waits on the phone until #1626 holds reviews; it replaces this binding with its own store. */
        @Provides
        fun providePendingReviews(): PendingContributorReviews = PendingContributorReviews {}
    }
}
