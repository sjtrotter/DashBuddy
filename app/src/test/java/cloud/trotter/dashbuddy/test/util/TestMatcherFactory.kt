package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.data.pay.PayParser
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.ScreenMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.AppStartupMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.DashAlongTheWayMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.DashPausedMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.DashSummaryMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.DeliverySummaryMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.DropoffNavigationMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.DropoffPreArrivalMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.IdleMapMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.OfferMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.PickupArrivalMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.PickupNavigationMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.PickupPreArrivalMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.PickupShoppingMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.ScheduleMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.SensitiveScreenMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.SetDashEndTimeMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.WaitingForOfferMatcher

object TestMatcherFactory {

    // Mimics the 'injectedMatchers' set provided by Hilt
    fun createAllMatchers(): Set<ScreenMatcher> {
        // Shared Dependencies (Mimic @Singleton)
        val payParser = PayParser()

        return setOf(
            // --- Complex Matchers (Dependency Injection) ---
            DeliverySummaryMatcher(payParser),

            // --- Simple Matchers ---
            AppStartupMatcher(),
            DashAlongTheWayMatcher(),
            DashPausedMatcher(),
            DashSummaryMatcher(),
            DropoffNavigationMatcher(),
            DropoffPreArrivalMatcher(),
            IdleMapMatcher(),
            OfferMatcher(),
            PickupArrivalMatcher(),
            PickupNavigationMatcher(),
            PickupPreArrivalMatcher(),
            PickupShoppingMatcher(),
            ScheduleMatcher(),
            SensitiveScreenMatcher(),
            SetDashEndTimeMatcher(),
            WaitingForOfferMatcher()
        )
    }
}