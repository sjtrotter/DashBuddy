package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.data.event.status.PickupStatus
import cloud.trotter.dashbuddy.pipeline.recognition.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenMatcher
import cloud.trotter.dashbuddy.services.accessibility.UiNode
import cloud.trotter.dashbuddy.util.UtilityFunctions

class PickupArrivalMatcher : ScreenMatcher {

    // Maps to your POST_ARRIVAL_PICKUP_SINGLE
    override val targetScreen = Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE

    // Priority 8 (Same as Pre-Arrival, they are mutually exclusive)
    override val priority = 8

    override fun matches(node: UiNode): ScreenInfo? {
        // 1. PRIMARY MATCHING: "Order for" + Customer Name
        val labelNode = node.findNode {
            it.viewIdResourceName?.endsWith("customer_name_label") == true &&
                    it.text?.contains("Order for", true) == true
        }

        if (labelNode == null) return null

        // 2. CONFIRMATION: Check Button Text
        // Should say "Confirm pickup", "Continue with pickup", or "Start pickup"
        val buttonTextNode = node.findNode {
            it.viewIdResourceName?.endsWith("textView_prism_button_title") == true
        }
        val buttonText = buttonTextNode?.text ?: ""

        val isConfirmScreen = buttonText.contains("Confirm", true) ||
                buttonText.contains("Continue", true) ||
                buttonText.contains("Start", true)

        if (!isConfirmScreen) return null

        // --- 3. DATA EXTRACTION ---

        // Customer Name (Stable ID)
        val customerNameNode =
            node.findNode { it.viewIdResourceName?.endsWith("customer_name") == true }
        val rawCustomerName = customerNameNode?.text
        val customerHash = if (!rawCustomerName.isNullOrBlank()) {
            UtilityFunctions.generateSha256(rawCustomerName)
        } else null

        // Store Name (Unstable ID: instructions_title)
        // We try to grab it, but we know it might be "Parking instructions".
        // Logic: If the text is short and doesn't contain "instructions", it's likely the store.
        val instructionTitleNode =
            node.findNode { it.viewIdResourceName?.endsWith("instructions_title") == true }
        val rawTitle = instructionTitleNode?.text

        val storeNameCandidate = if (
            !rawTitle.isNullOrBlank() &&
            !rawTitle.contains("instructions", true) &&
            !rawTitle.contains("notes", true)
        ) {
            rawTitle
        } else {
            null
        }

        // --- 4. RETURN ---
        return ScreenInfo.PickupDetails(
            screen = targetScreen,
            storeName = storeNameCandidate, // Might be null, Handler will use "Sticky" logic
            customerNameHash = customerHash, // We add this field to PickupDetails for tracking
            status = PickupStatus.ARRIVED // We are definitely arrived if we see "Confirm"
        )
    }
}