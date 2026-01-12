package cloud.trotter.dashbuddy.pipeline.recognition

import cloud.trotter.dashbuddy.pipeline.recognition.matchers.*
import cloud.trotter.dashbuddy.services.accessibility.UiNode
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo

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
    )

    fun identify(node: UiNode): ScreenInfo {
        return matchers
            .sortedByDescending { it.priority }
            .firstNotNullOfOrNull { it.matches(node) }
            ?: ScreenInfo.Simple(Screen.UNKNOWN)
    }
}