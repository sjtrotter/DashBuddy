package cloud.trotter.dashbuddy.di

import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.AppStartupMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.ChatViewMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DashAlongTheWayMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DashPausedMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DashSummaryMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DeliverySummaryMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DropoffNavigationMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DropoffPreArrivalMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.EarningsViewMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.HelpViewMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.IdleMapMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.NavigationViewMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.NotificationsViewMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.OfferMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.OnDashMapMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupArrivalMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupDetailsPickedUpMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupDetailsPostArrivalMultiMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupDetailsPreArrivalMultiMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupDetailsVerifyPickupMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupNavigationMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupPreArrivalMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupShoppingMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PromosViewMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.RatingsViewMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.SafetyViewMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.ScheduleMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.SensitiveScreenMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.SetDashEndTimeMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.TimelineViewMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.WaitingForOfferMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.DashAlongTheWayParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.DashPausedParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.DashSummaryParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.DeliverySummaryParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.DropoffNavigationParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.DropoffPreArrivalParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.IdleMapParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.OfferParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.PickupArrivalParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.PickupNavigationParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.PickupPreArrivalParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.PickupShoppingParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.SensitiveScreenParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.SetDashEndTimeParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.WaitingForOfferParser
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PipelineModule {

    // =========================================================================
    // MATCHERS
    // =========================================================================

    // --- Complex matchers (injected dependencies) ---

    @Binds @IntoSet @Singleton
    abstract fun bindDeliverySummaryMatcher(impl: DeliverySummaryMatcher): ScreenMatcher

    // --- Structural matchers (no dependencies) ---

    @Binds @IntoSet @Singleton
    abstract fun bindAppStartupMatcher(impl: AppStartupMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindDashAlongTheWayMatcher(impl: DashAlongTheWayMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindDashPausedMatcher(impl: DashPausedMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindDashSummaryMatcher(impl: DashSummaryMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindDropoffNavigationMatcher(impl: DropoffNavigationMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindDropoffPreArrivalMatcher(impl: DropoffPreArrivalMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindIdleMapMatcher(impl: IdleMapMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindOfferMatcher(impl: OfferMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindOnDashMapMatcher(impl: OnDashMapMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindPickupArrivalMatcher(impl: PickupArrivalMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindPickupNavigationMatcher(impl: PickupNavigationMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindPickupPreArrivalMatcher(impl: PickupPreArrivalMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindPickupShoppingMatcher(impl: PickupShoppingMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindScheduleMatcher(impl: ScheduleMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindSensitiveScreenMatcher(impl: SensitiveScreenMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindSetDashEndTimeMatcher(impl: SetDashEndTimeMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindWaitingForOfferMatcher(impl: WaitingForOfferMatcher): ScreenMatcher

    // --- Legacy-replacement matchers (text-based, priority 3) ---

    @Binds @IntoSet @Singleton
    abstract fun bindChatViewMatcher(impl: ChatViewMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindEarningsViewMatcher(impl: EarningsViewMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindHelpViewMatcher(impl: HelpViewMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindNavigationViewMatcher(impl: NavigationViewMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindNotificationsViewMatcher(impl: NotificationsViewMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindPickupDetailsPickedUpMatcher(impl: PickupDetailsPickedUpMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindPickupDetailsPostArrivalMultiMatcher(impl: PickupDetailsPostArrivalMultiMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindPickupDetailsPreArrivalMultiMatcher(impl: PickupDetailsPreArrivalMultiMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindPickupDetailsVerifyPickupMatcher(impl: PickupDetailsVerifyPickupMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindPromosViewMatcher(impl: PromosViewMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindRatingsViewMatcher(impl: RatingsViewMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindSafetyViewMatcher(impl: SafetyViewMatcher): ScreenMatcher

    @Binds @IntoSet @Singleton
    abstract fun bindTimelineViewMatcher(impl: TimelineViewMatcher): ScreenMatcher

    // =========================================================================
    // PARSERS
    // =========================================================================

    @Binds @IntoSet @Singleton
    abstract fun bindDashAlongTheWayParser(impl: DashAlongTheWayParser): ScreenParser

    @Binds @IntoSet @Singleton
    abstract fun bindDashPausedParser(impl: DashPausedParser): ScreenParser

    @Binds @IntoSet @Singleton
    abstract fun bindDashSummaryParser(impl: DashSummaryParser): ScreenParser

    @Binds @IntoSet @Singleton
    abstract fun bindDeliverySummaryParser(impl: DeliverySummaryParser): ScreenParser

    @Binds @IntoSet @Singleton
    abstract fun bindDropoffNavigationParser(impl: DropoffNavigationParser): ScreenParser

    @Binds @IntoSet @Singleton
    abstract fun bindDropoffPreArrivalParser(impl: DropoffPreArrivalParser): ScreenParser

    @Binds @IntoSet @Singleton
    abstract fun bindIdleMapParser(impl: IdleMapParser): ScreenParser

    @Binds @IntoSet @Singleton
    abstract fun bindOfferParser(impl: OfferParser): ScreenParser

    @Binds @IntoSet @Singleton
    abstract fun bindPickupArrivalParser(impl: PickupArrivalParser): ScreenParser

    @Binds @IntoSet @Singleton
    abstract fun bindPickupNavigationParser(impl: PickupNavigationParser): ScreenParser

    @Binds @IntoSet @Singleton
    abstract fun bindPickupPreArrivalParser(impl: PickupPreArrivalParser): ScreenParser

    @Binds @IntoSet @Singleton
    abstract fun bindPickupShoppingParser(impl: PickupShoppingParser): ScreenParser

    @Binds @IntoSet @Singleton
    abstract fun bindSensitiveScreenParser(impl: SensitiveScreenParser): ScreenParser

    @Binds @IntoSet @Singleton
    abstract fun bindSetDashEndTimeParser(impl: SetDashEndTimeParser): ScreenParser

    @Binds @IntoSet @Singleton
    abstract fun bindWaitingForOfferParser(impl: WaitingForOfferParser): ScreenParser
}
