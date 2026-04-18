package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import timber.log.Timber
import javax.inject.Inject

class OnDashMapMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.MAIN_MAP_ON_DASH
    override val priority = 2

    override fun matches(node: UiNode): ScreenInfo? {
        // --- 1. NEGATIVE MATCHING (Safety Guards) ---

        // Safety Guard 1: Earnings Mode Switcher — this screen belongs to IdleMapMatcher.
        val hasEarningsSwitcher = node.findNode {
            it.contentDescription == "Earnings Mode Switcher"
        } != null

        if (hasEarningsSwitcher) {
            Timber.v("Match failed: Earnings Mode Switcher detected (MAIN_MAP_IDLE).")
            return null
        }

        // Safety Guard 2: "Looking for offers" / "Finding offers" — belongs to WaitingForOfferMatcher.
        val isWaitingForOffer = node.findNode {
            it.text?.contains("looking for offers", ignoreCase = true) == true ||
                    it.text?.contains("finding offers", ignoreCase = true) == true
        } != null

        if (isWaitingForOffer) {
            Timber.v("Match failed: Waiting-for-offer UI detected (ON_DASH_MAP_WAITING_FOR_OFFER).")
            return null
        }

        // --- 2. POSITIVE MATCHING ---

        // The "Return to dash" button is the definitive signal that the dasher is mid-dash
        // and has navigated away from the active order screen to the home map.
        val hasReturnToDash = node.findNode {
            it.text.equals("Return to dash", ignoreCase = true)
        } != null

        if (!hasReturnToDash) return null

        return ScreenInfo.Simple(Screen.MAIN_MAP_ON_DASH)
    }
}
