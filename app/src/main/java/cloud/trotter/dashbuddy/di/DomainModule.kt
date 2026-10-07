package cloud.trotter.dashbuddy.di

import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
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

    /**
     * The wall clock (#1271): the classifier, the side-effect engine, the event repo, the snapshot
     * store, the state manager and the offer-action receiver read their instants through it. System
     * UTC is exactly what `System.currentTimeMillis()` returned — only millis are read, never the
     * zone — so this binding changes no production behaviour; it exists so the end-to-end harness
     * can bind a virtual clock.
     */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    // Future Note: As you build more pure Domain classes (like UseCases),
    // you will just add more @Provides functions to this exact file.
}