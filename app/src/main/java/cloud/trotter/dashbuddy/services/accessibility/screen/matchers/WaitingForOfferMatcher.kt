package cloud.trotter.dashbuddy.services.accessibility.screen.matchers

import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenMatcher
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.util.UtilityFunctions

class WaitingForOfferMatcher : ScreenMatcher {

    override val targetScreen = Screen.ON_DASH_MAP_WAITING_FOR_OFFER
    override val priority = 1

    override fun matches(context: StateContext): ScreenInfo? {
        val root = context.rootUiNode ?: return null

        // --- 1. MATCHING LOGIC ---

        // Criterion A: The specific progress bar ID
        // This appears in Loading, Heading Back, Normal, and With Pay variants
        val hasProgressBar = root.findNode {
            it.viewIdResourceName?.endsWith("looking_for_order_progress_bar") == true
        } != null

        // Criterion B: The Title Text
        // "Looking for offers" appears in the top text view.
        val hasTitle = root.findNode {
            it.text.equals("Looking for offers", ignoreCase = true)
        } != null

        // Criterion C: Forbidden texts (Safety check)
        // Ensure we aren't on the "Dash Along The Way" start screen
        val hasAlongWayText = root.findNode {
            it.text?.contains("look for orders along the way", ignoreCase = true) == true
        } != null

        // Must have at least one strong signal (Bar or Title) AND not be "Along the Way"
        if ((!hasProgressBar && !hasTitle) || hasAlongWayText) {
            return null
        }

        // --- 2. PARSING LOGIC ---

        // A. Check for "Heading Back to Zone"
        // id="cross_sp_title" with text "Finding orders headed back to zone"
        val isHeadingBack = root.findNode {
            it.viewIdResourceName?.endsWith("cross_sp_title") == true
        } != null

        // B. Extract Wait Time
        // Found inside button id="wait_time_button" -> TextView id="textView_prism_button_title"
        // Text is like "est. 1-4 min"
        var waitTime: String? = null
        val waitTimeNode = root.findNode {
            it.viewIdResourceName?.endsWith("wait_time_button") == true
        }
        if (waitTimeNode != null) {
            waitTime = waitTimeNode.children.firstOrNull {
                it.viewIdResourceName?.endsWith("textView_prism_button_title") == true
            }?.text?.replace("est. ", "")
        }

        // C. Extract Running Total
        // id="running_total_pay", text="$0.00"
        val payNode = root.findNode {
            it.viewIdResourceName?.endsWith("running_total_pay") == true
        }
        val currentPay = UtilityFunctions.parseCurrency(payNode?.text)

        return ScreenInfo.WaitingForOffer(
            screen = Screen.ON_DASH_MAP_WAITING_FOR_OFFER,
            currentDashPay = currentPay,
            waitTimeEstimate = waitTime,
            isHeadingBackToZone = isHeadingBack
        )
    }
}