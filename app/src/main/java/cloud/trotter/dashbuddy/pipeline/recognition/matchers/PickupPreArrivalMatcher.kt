package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.data.event.status.PickupStatus
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenMatcher
import cloud.trotter.dashbuddy.services.accessibility.UiNode

class PickupPreArrivalMatcher : ScreenMatcher {

    override val targetScreen = Screen.PICKUP_DETAILS_PRE_ARRIVAL

    // Priority 8: Check this after Navigation (10), but before Generic Map (1)
    override val priority = 8

    override fun matches(node: UiNode): ScreenInfo? {
        // --- 1. PRIMARY MATCHING (Fail Fast) ---

        // Check Label: "Pickup from"
        // We strict match this to ensure we aren't on a Dropoff screen (which says "Delivery for")
        val labelNode = node.findNode {
            it.viewIdResourceName?.endsWith("user_name_label") == true &&
                    it.text?.contains("Pickup from", true) == true
        }

        if (labelNode == null) {
            return null
        }

        // Check Button Text: Look for the specific text ID
        // It should contain "Directions" OR "Arrived at store"
        val buttonTextNode = node.findNode {
            it.viewIdResourceName?.endsWith("textView_prism_button_title") == true
        }
        val buttonText = buttonTextNode?.text ?: ""

        val isPreArrival = buttonText.equals("Directions", true) ||
                buttonText.equals("Arrived at store", true)

        if (!isPreArrival) {
            return null
        }

        // --- 2. DATA EXTRACTION ---

        // Store Name (ID: user_name)
        // Since we confirmed the "Pickup from" label exists, searching for 'user_name' is safe.
        val storeNameNode = node.findNode { it.viewIdResourceName?.endsWith("user_name") == true }
        val storeName = storeNameNode?.text

        // Address (IDs: address_line_1, address_line_2)
        val address1 =
            node.findNode { it.viewIdResourceName?.endsWith("address_line_1") == true }?.text
        val address2 =
            node.findNode { it.viewIdResourceName?.endsWith("address_line_2") == true }?.text

        // Validation: If vital data is missing, we shouldn't match
        if (storeName.isNullOrBlank()) {
            return null
        }

        // --- 3. CLEANUP & RETURN ---

        // Address Cleanup: If Line 1 is just the Store Name again, ignore it.
        val cleanAddress1 = if (address1.equals(storeName, ignoreCase = true)) null else address1

        val fullAddress = listOfNotNull(cleanAddress1, address2)
            .filter { it.isNotBlank() }
            .joinToString(", ")

        // Validation: Ensure we actually have an address
        if (fullAddress.isBlank()) {
            return null
        }

        // Status is ALWAYS Navigating for this screen.
        // Even if the button says "Arrived at store", we haven't clicked it yet, so we are still traveling.
        return ScreenInfo.PickupDetails(
            screen = targetScreen,
            storeName = storeName,
            storeAddress = fullAddress,
            status = PickupStatus.NAVIGATING
        )
    }
}