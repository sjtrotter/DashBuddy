package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
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

object TestMatcherFactory {

    // Mimics the 'injectedMatchers' set provided by Hilt
    fun createAllMatchers(): Set<ScreenMatcher> {
        return setOf(
            // Structural matchers
            AppStartupMatcher(),
            DashAlongTheWayMatcher(),
            DashPausedMatcher(),
            DashSummaryMatcher(),
            DeliverySummaryMatcher(),
            DropoffNavigationMatcher(),
            DropoffPreArrivalMatcher(),
            IdleMapMatcher(),
            OfferMatcher(),
            OnDashMapMatcher(),
            PickupArrivalMatcher(),
            PickupNavigationMatcher(),
            PickupPreArrivalMatcher(),
            PickupShoppingMatcher(),
            ScheduleMatcher(),
            SensitiveScreenMatcher(),
            SetDashEndTimeMatcher(),
            WaitingForOfferMatcher(),

            // Legacy-replacement matchers (text-based)
            ChatViewMatcher(),
            EarningsViewMatcher(),
            HelpViewMatcher(),
            NavigationViewMatcher(),
            NotificationsViewMatcher(),
            PickupDetailsPickedUpMatcher(),
            PickupDetailsPostArrivalMultiMatcher(),
            PickupDetailsPreArrivalMultiMatcher(),
            PickupDetailsVerifyPickupMatcher(),
            PromosViewMatcher(),
            RatingsViewMatcher(),
            SafetyViewMatcher(),
            TimelineViewMatcher()
        )
    }
}
