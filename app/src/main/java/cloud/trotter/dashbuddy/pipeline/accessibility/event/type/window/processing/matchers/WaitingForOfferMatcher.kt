package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import cloud.trotter.dashbuddy.util.UtilityFunctions
import javax.inject.Inject

class WaitingForOfferMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.ON_DASH_MAP_WAITING_FOR_OFFER
    override val priority = 1

    override fun matches(node: UiNode): ScreenInfo? {
        // --- 1. SAFETY CHECKS ---

        // Guard: Earnings Mode Switcher — this screen belongs to IdleMapMatcher.
        val hasEarningsSwitcher = node.findNode {
            it.contentDescription == "Earnings Mode Switcher"
        } != null

        if (hasEarningsSwitcher) return null

        // Guard: "Return to dash" — this screen belongs to OnDashMapMatcher.
        val hasReturnToDash = node.findNode {
            it.text.equals("Return to dash", ignoreCase = true)
        } != null

        if (hasReturnToDash) return null

        // Guard: Ensure we aren't on the "Dash Along The Way" start screen.
        val hasAlongWayText = node.findNode {
            it.text?.contains("look for orders along the way", ignoreCase = true) == true
        } != null

        if (hasAlongWayText) return null

        // --- 2. ISOLATED MATCHING & PARSING ---
        // Try the legacy layout first. If it returns null, try the new layout.
        return parseLegacyLayout(node) ?: parseNewLayout(node)
    }

    private fun parseLegacyLayout(node: UiNode): ScreenInfo.WaitingForOffer? {
        // MATCHING
        val hasProgressBar = node.findNode {
            it.viewIdResourceName?.endsWith("looking_for_order_progress_bar") == true
        } != null

        val hasTitle = node.findNode {
            it.text.equals("Looking for offers", ignoreCase = true)
        } != null

        if (!hasProgressBar && !hasTitle) return null

        // PARSING
        val isHeadingBack = node.findNode {
            it.viewIdResourceName?.endsWith("cross_sp_title") == true
        } != null

        var waitTime: String? = null
        val waitTimeNode = node.findNode {
            it.viewIdResourceName?.endsWith("wait_time_button") == true
        }
        if (waitTimeNode != null) {
            waitTime = waitTimeNode.children.firstOrNull {
                it.viewIdResourceName?.endsWith("textView_prism_button_title") == true
            }?.text?.replace("est. ", "")
        }

        val payNode = node.findNode {
            it.viewIdResourceName?.endsWith("running_total_pay") == true
        }
        val currentPay = UtilityFunctions.parseCurrency(payNode?.text)

        return ScreenInfo.WaitingForOffer(
            screen = targetScreen,
            currentDashPay = currentPay,
            waitTimeEstimate = waitTime,
            isHeadingBackToZone = isHeadingBack
        )
    }

    private fun parseNewLayout(node: UiNode): ScreenInfo.WaitingForOffer? {
        // MATCHING
        val isNewLayout = node.findNode {
            it.text?.contains("Finding offers", ignoreCase = true) == true
        } != null

        if (!isNewLayout) return null

        // PARSING
        // Because the new layout uses a flipping UI dot, extracting the pay is volatile.
        // We safely return null for the stats here to protect the pipeline, while still
        // successfully identifying the screen state for the State Machine!

        return ScreenInfo.WaitingForOffer(
            screen = targetScreen,
            currentDashPay = null,       // Ignored due to flipping UI
            waitTimeEstimate = null,     // Not extracted in the new UI yet
            isHeadingBackToZone = false  // Default to false until we find the new "Heading Back" node
        )
    }
}