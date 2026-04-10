package cloud.trotter.dashbuddy.di

import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DomainModule {

    @Provides
    @Singleton
    fun provideOfferEvaluator(): OfferEvaluator {
        // Because it has an empty constructor, it's trivial to build!
        return OfferEvaluator()
    }

    // Future Note: As you build more pure Domain classes (like UseCases),
    // you will just add more @Provides functions to this exact file.
}