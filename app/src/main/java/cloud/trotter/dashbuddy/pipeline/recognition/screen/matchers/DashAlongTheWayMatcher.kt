package cloud.trotter.dashbuddy.pipeline.recognition.screen.matchers

import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.screen.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenMatcher
import javax.inject.Inject

class DashAlongTheWayMatcher @Inject constructor() : ScreenMatcher {

    // functionally we are waiting for an offer, even if we are navigating
    override val targetScreen = Screen.ON_DASH_ALONG_THE_WAY

    // Check this BEFORE the generic WaitingForOffer matcher
    override val priority = 2

    override fun matches(node: UiNode): ScreenInfo? {
        // --- 1. ID-BASED MATCHING ---
        // Using the "Smoking Gun" IDs from your log

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

        // Logic: Must have Navigate Button AND (Specific Title OR Spot Saved Info)
        if (!hasNavigateButton || (!hasAlongWayTitle && !hasSpotSavedInfo)) {
            return null
        }

        // --- 2. RETURN INFO ---
        // We map this to WaitingForOffer so the Reducer handles it automatically.
        // We flag 'isHeadingBackToZone' as false, because this is a forward-navigation state.

        return ScreenInfo.WaitingForOffer(
            screen = targetScreen,
            currentDashPay = null, // Pay is usually not shown on this specific overlay
            waitTimeEstimate = null, // No wait time estimate on this screen
            isHeadingBackToZone = false
        )
    }
}