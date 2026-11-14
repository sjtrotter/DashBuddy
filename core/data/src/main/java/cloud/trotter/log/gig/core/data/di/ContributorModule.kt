package cloud.trotter.log.gig.core.data.di

import cloud.trotter.log.gig.core.data.census.contributor.CensusContributor
import cloud.trotter.log.gig.core.data.census.contributor.ContributorReviewQueue
import cloud.trotter.log.gig.domain.census.contributor.ContributorGate
import cloud.trotter.log.gig.domain.census.contributor.ContributorReviewIntake
import cloud.trotter.log.gig.domain.census.contributor.PendingContributorReviews
import cloud.trotter.log.gig.domain.census.contributor.UploadedSkeletons
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** #1625: the contributor gate. #1626: the review queue is the reviews waiting on the phone, fed by the census sink and the upload worker. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ContributorModule {
    @Binds
    abstract fun bindContributorGate(impl: CensusContributor): ContributorGate

    @Binds
    abstract fun bindPendingReviews(impl: ContributorReviewQueue): PendingContributorReviews

    @Binds
    abstract fun bindReviewIntake(impl: ContributorReviewQueue): ContributorReviewIntake

    @Binds
    abstract fun bindUploadedSkeletons(impl: ContributorReviewQueue): UploadedSkeletons
}
