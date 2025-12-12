package cloud.trotter.dashbuddy.services.accessibility.screen

import cloud.trotter.dashbuddy.services.accessibility.screen.matchers.*
import cloud.trotter.dashbuddy.state.StateContext

object ScreenRecognizerV2 {

    // Define the list of screens we have ALREADY converted to dedicated Matchers.
    // We must exclude these from the Legacy list to prevent "Empty Enum" false positives.
    private val REFACTORED_SCREENS = setOf(
        Screen.APP_STARTING_OR_LOADING,
        Screen.MAIN_MAP_IDLE,
        Screen.SCHEDULE_VIEW,
        Screen.NAVIGATION_VIEW_TO_PICK_UP,
        Screen.NAVIGATION_VIEW_TO_DROP_OFF,
//        Screen.OFFER_POPUP,
        Screen.PICKUP_DETAILS_PRE_ARRIVAL,
        Screen.PICKUP_DETAILS_POST_ARRIVAL_SHOP,
        Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE,
        Screen.DROPOFF_DETAILS_PRE_ARRIVAL,
        Screen.DELIVERY_SUMMARY_COLLAPSED,
        Screen.DELIVERY_SUMMARY_EXPANDED,
        Screen.SET_DASH_END_TIME,
        // Add others here as you finish refactoring them (e.g. PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE)
    )

    private val matchers: List<ScreenMatcher> = buildList {
        // 1. Add New, Efficient Matchers
        add(AppStartupMatcher())
        add(DeliverySummaryMatcher())
        add(DropoffNavigationMatcher())
        add(IdleMapMatcher())
        add(OfferMatcher())
        add(PickupArrivalMatcher())
        add(PickupNavigationMatcher())
        // add(PickupConfirmMatcher()) // Don't forget to add this if you implemented it!
        add(PickupShoppingMatcher())
        add(ScheduleMatcher())
        add(PickupPreArrivalMatcher())
        add(DropoffPreArrivalMatcher())
        add(SetDashEndTimeMatcher())

        // 2. Add Legacy Bridge (Filtering out the ones we just added above)
        // This prevents the "Empty Enum" bug.
        val legacy = Screen.entries
            .filterNot { it in REFACTORED_SCREENS }
            .filterNot { it == Screen.UNKNOWN } // Skip UNKNOWN
            .map { LegacyEnumMatcher(it) }

        addAll(legacy)
    }

    fun identify(stateContext: StateContext): ScreenInfo {
        return matchers
            .sortedByDescending { it.priority }
            .firstNotNullOfOrNull { it.matches(stateContext) }
            ?: ScreenInfo.Simple(Screen.UNKNOWN)
    }
}