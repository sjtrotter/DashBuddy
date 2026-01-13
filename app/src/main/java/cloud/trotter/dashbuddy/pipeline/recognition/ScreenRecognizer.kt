package cloud.trotter.dashbuddy.pipeline.recognition

import cloud.trotter.dashbuddy.pipeline.recognition.matchers.*
import cloud.trotter.dashbuddy.services.accessibility.UiNode

object ScreenRecognizer {

    private val matchers = listOf(
        AppStartupMatcher(),
        DeliverySummaryMatcher(),
        DropoffNavigationMatcher(),
        IdleMapMatcher(),
        OfferMatcher(),
        PickupArrivalMatcher(),
        PickupNavigationMatcher(),
        PickupShoppingMatcher(),
        ScheduleMatcher(),
        PickupPreArrivalMatcher(),
        DropoffPreArrivalMatcher(),
        SetDashEndTimeMatcher(),
        DashSummaryMatcher(),
        WaitingForOfferMatcher(),
        DashPausedMatcher(),
        DashAlongTheWayMatcher(),
    ).sortedByDescending { it.priority }

    private val legacy = Screen.entries
        .filter { it.hasMatchingCriteria }
        .map { LegacyEnumMatcher(it) }

    fun identify(node: UiNode): ScreenInfo {
        return matchers
            .firstNotNullOfOrNull { it.matches(node) }
            ?: legacy.firstNotNullOfOrNull { it.matches(node) }
            ?: ScreenInfo.Simple(Screen.UNKNOWN)
    }
}