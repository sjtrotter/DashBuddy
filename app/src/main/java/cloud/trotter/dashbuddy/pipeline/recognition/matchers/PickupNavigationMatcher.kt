package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.data.event.status.PickupStatus
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenMatcher
import cloud.trotter.dashbuddy.state.StateContext

class PickupNavigationMatcher : ScreenMatcher {

    override val targetScreen = Screen.NAVIGATION_VIEW_TO_PICK_UP

    // High priority to catch this overlay state
    override val priority = 10

    override fun matches(context: StateContext): ScreenInfo? {
        val root = context.rootUiNode ?: return null

        // 1. ANCHOR: Find the Navigation Title ("Heading to...")
        val navTitleNode = root.findNode {
            it.viewIdResourceName?.endsWith("bottom_sheet_task_title") == true
        } ?: return null

        // 2. DISAMBIGUATION: Pickup vs Dropoff
        // Both screens share the same IDs. We must check the text content.
        val titleText = navTitleNode.text ?: ""

        // If it says "Deliver to...", this is a Dropoff. Fail fast.
        if (titleText.contains("Deliver to", ignoreCase = true)) {
            return null
        }

        // Optional double-check: Look for "Pick up by" vs "Deliver by"
        val timeNode = root.findNode {
            it.viewIdResourceName?.endsWith("bottom_sheet_task_arrive_by") == true
        }
        if (timeNode?.text?.contains("Deliver by", ignoreCase = true) == true) {
            return null
        }

        // 3. PARSING: We are confirmed on Pickup Navigation.

        // Store Name: "Heading to McDonald's" -> "McDonald's"
        val storeName = titleText.replace("Heading to ", "", ignoreCase = true).trim()

        // Address: Extract line 1 (and 2 if exists)
        val address1 =
            root.findNode { it.viewIdResourceName?.endsWith("bottom_sheet_address_line_1") == true }?.text
        val address2 =
            root.findNode { it.viewIdResourceName?.endsWith("bottom_sheet_address_line_2") == true }?.text

        val fullAddress = listOfNotNull(address1, address2)
            .filter { it.isNotBlank() }
            .joinToString(", ")

        // 4. RETURN
        return ScreenInfo.PickupDetails(
            screen = targetScreen,
            storeName = storeName,
            storeAddress = fullAddress.ifBlank { null },
            status = PickupStatus.NAVIGATING
        )
    }
}