package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class DashAlongTheWayMatcher @Inject constructor() : ScreenMatcher {

    // functionally we are waiting for an offer, even if we are navigating
    override val targetScreen = Screen.ON_DASH_ALONG_THE_WAY

    // Check this BEFORE the generic WaitingForOffer matcher
    override val priority = 2

    override fun matches(node: UiNode): Screen? {
        // A. Specific Title: "We'll look for orders along the way"
        val hasAlongWayTitle = node.hasNode {
            it.viewIdResourceName?.endsWith("bottom_view_info_ctd_v2_title") == true
        }

        // B. "Navigate" Button
        val hasNavigateButton = node.hasNode {
            it.viewIdResourceName?.endsWith("navigate_button") == true
        }

        // C. Fallback: Spot Saved Info (if title varies)
        val hasSpotSavedInfo = node.hasNode {
            it.viewIdResourceName?.endsWith("bottom_view_info_title") == true
        }

        // Must have Navigate Button AND (Specific Title OR Spot Saved Info)
        return if (hasNavigateButton && (hasAlongWayTitle || hasSpotSavedInfo)) targetScreen else null
    }
}