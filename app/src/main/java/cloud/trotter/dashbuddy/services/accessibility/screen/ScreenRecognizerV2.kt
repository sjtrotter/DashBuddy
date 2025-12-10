package cloud.trotter.dashbuddy.services.accessibility.screen

import cloud.trotter.dashbuddy.services.accessibility.screen.matchers.AppStartupMatcher
import cloud.trotter.dashbuddy.services.accessibility.screen.matchers.DeliverySummaryMatcher
import cloud.trotter.dashbuddy.services.accessibility.screen.matchers.DropoffNavigationMatcher
import cloud.trotter.dashbuddy.services.accessibility.screen.matchers.IdleMapMatcher
import cloud.trotter.dashbuddy.services.accessibility.screen.matchers.LegacyEnumMatcher
import cloud.trotter.dashbuddy.services.accessibility.screen.matchers.PickupArrivalMatcher
import cloud.trotter.dashbuddy.services.accessibility.screen.matchers.PickupNavigationMatcher
import cloud.trotter.dashbuddy.services.accessibility.screen.matchers.PickupPreArrivalMatcher
import cloud.trotter.dashbuddy.services.accessibility.screen.matchers.PickupShoppingMatcher
import cloud.trotter.dashbuddy.services.accessibility.screen.matchers.ScheduleMatcher
import cloud.trotter.dashbuddy.state.StateContext

object ScreenRecognizerV2 {

    // The Registry
    private val matchers: List<ScreenMatcher> = listOf(
        // New, efficient matchers
        AppStartupMatcher(),
        DeliverySummaryMatcher(),
        DropoffNavigationMatcher(),
        IdleMapMatcher(),
        // LegacyEnumMatcher (below)
        PickupArrivalMatcher(),
        PickupNavigationMatcher(),
        PickupPreArrivalMatcher(),
        PickupShoppingMatcher(),
        ScheduleMatcher(),
        // Add in matchers as they are created.

        // The catch-all for everything else defined in your Enum
        *Screen.entries.map { LegacyEnumMatcher(it) }.toTypedArray()
    )

    fun identify(stateContext: StateContext): ScreenInfo {
        // Find the first matcher that returns a non-null ScreenInfo
        return matchers
            .sortedByDescending { it.priority }
            .firstNotNullOfOrNull { it.matches(stateContext) }
            ?: ScreenInfo.Simple(Screen.UNKNOWN)
    }
}