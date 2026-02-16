package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.data.pay.PayParser
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