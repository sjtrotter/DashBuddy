package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class WaitingForOfferMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.ON_DASH_MAP_WAITING_FOR_OFFER
    override val priority = 1

    override fun matches(node: UiNode): Screen? {
        // Guard: Earnings Mode Switcher — belongs to IdleMapMatcher.
        if (node.findNode { it.contentDescription == "Earnings Mode Switcher" } != null) return null

        // Guard: "Return to dash" — belongs to OnDashMapMatcher.
        if (node.findNode { it.text.equals("Return to dash", ignoreCase = true) } != null) return null

        // Guard: "Along the way" — belongs to DashAlongTheWayMatcher.
        if (node.findNode { it.text?.contains("look for orders along the way", ignoreCase = true) == true } != null) return null

        // Legacy layout: progress bar OR "Looking for offers" title.
        val hasProgressBar = node.findNode {
            it.viewIdResourceName?.endsWith("looking_for_order_progress_bar") == true
        } != null
        val hasLegacyTitle = node.findNode {
            it.text.equals("Looking for offers", ignoreCase = true)
        } != null
        if (hasProgressBar || hasLegacyTitle) return targetScreen

        // New layout: "Finding offers" text.
        val isNewLayout = node.findNode {
            it.text?.contains("Finding offers", ignoreCase = true) == true
        } != null
        return if (isNewLayout) targetScreen else null
    }
}