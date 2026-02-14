package cloud.trotter.dashbuddy.di

import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.AppStartupMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DashAlongTheWayMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DashPausedMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DashSummaryMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DeliverySummaryMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DropoffNavigationMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DropoffPreArrivalMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.IdleMapMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.OfferMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupArrivalMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupNavigationMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupPreArrivalMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupShoppingMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.ScheduleMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.SensitiveScreenMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.SetDashEndTimeMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.WaitingForOfferMatcher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PipelineModule {

    // --- 1. Complex Matchers (With Dependencies) ---
    @Binds
    @IntoSet
    @Singleton
    abstract fun bindDeliverySummaryMatcher(impl: DeliverySummaryMatcher): ScreenMatcher

    // --- 2. Simple Matchers (No Dependencies yet, but still need Injection) ---
    @Binds
    @IntoSet
    @Singleton
    abstract fun bindAppStartupMatcher(impl: AppStartupMatcher): ScreenMatcher

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindDropoffNavigationMatcher(impl: DropoffNavigationMatcher): ScreenMatcher

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindIdleMapMatcher(impl: IdleMapMatcher): ScreenMatcher

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindOfferMatcher(impl: OfferMatcher): ScreenMatcher

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindPickupArrivalMatcher(impl: PickupArrivalMatcher): ScreenMatcher

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindPickupNavigationMatcher(impl: PickupNavigationMatcher): ScreenMatcher

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindPickupShoppingMatcher(impl: PickupShoppingMatcher): ScreenMatcher

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindScheduleMatcher(impl: ScheduleMatcher): ScreenMatcher

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindPickupPreArrivalMatcher(impl: PickupPreArrivalMatcher): ScreenMatcher

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindDropoffPreArrivalMatcher(impl: DropoffPreArrivalMatcher): ScreenMatcher

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindSetDashEndTimeMatcher(impl: SetDashEndTimeMatcher): ScreenMatcher

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindDashSummaryMatcher(impl: DashSummaryMatcher): ScreenMatcher

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindWaitingForOfferMatcher(impl: WaitingForOfferMatcher): ScreenMatcher

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindDashPausedMatcher(impl: DashPausedMatcher): ScreenMatcher

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindDashAlongTheWayMatcher(impl: DashAlongTheWayMatcher): ScreenMatcher

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindSensitiveScreenMatcher(impl: SensitiveScreenMatcher): ScreenMatcher
}