package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.data.pay.PayParser
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.AppStartupMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.DashAlongTheWayMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.DashPausedMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.DashSummaryMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.DeliverySummaryMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.DropoffNavigationMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.DropoffPreArrivalMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.IdleMapMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.OfferMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.PickupArrivalMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.PickupNavigationMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.PickupPreArrivalMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.PickupShoppingMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.ScheduleMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.SensitiveScreenMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.SetDashEndTimeMatcher
import cloud.trotter.dashbuddy.pipeline.recognition.matchers.WaitingForOfferMatcher

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