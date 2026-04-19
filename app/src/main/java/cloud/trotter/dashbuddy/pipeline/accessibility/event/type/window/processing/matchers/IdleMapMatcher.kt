package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import timber.log.Timber
import javax.inject.Inject

class IdleMapMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.MAIN_MAP_IDLE
    override val priority = 1

    override fun matches(node: UiNode): Screen? {
        // Guard 1: "Return to dash" — belongs to OnDashMapMatcher.
        if (node.findNode { it.text.equals("Return to dash", ignoreCase = true) } != null) {
            Timber.v("Match failed: 'Return to dash' detected (MAIN_MAP_ON_DASH).")
            return null
        }

        // Guard 2: Waiting-for-offer signals — belongs to WaitingForOfferMatcher.
        if (node.findNode {
                it.text?.contains("looking for offers", ignoreCase = true) == true ||
                        it.text?.contains("finding offers", ignoreCase = true) == true
            } != null) {
            Timber.v("Match failed: Waiting-for-offer UI detected.")
            return null
        }

        // Positive: Earnings Mode Switcher + Side Menu.
        val hasEarningsSwitcher = node.findNode { it.contentDescription == "Earnings Mode Switcher" } != null
        val hasSideMenu = node.findNode {
            it.viewIdResourceName?.endsWith("side_nav_compose_view") == true ||
                    it.contentDescription == "Side Menu"
        } != null

        return if (hasEarningsSwitcher && hasSideMenu) targetScreen else null
    }
}