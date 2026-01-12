package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.data.event.status.DropoffStatus
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenMatcher
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.util.UtilityFunctions
import cloud.trotter.dashbuddy.log.Logger as Log

class DropoffNavigationMatcher : ScreenMatcher {

    override val targetScreen = Screen.NAVIGATION_VIEW_TO_DROP_OFF
    private val tag = "DropoffNavMatcher"

    // High priority to catch this before generic maps
    override val priority = 10

    override fun matches(context: StateContext): ScreenInfo? {
        val root = context.rootUiNode ?: return null

        // 1. ANCHOR: Find the Navigation Title
        // Common ID: bottom_sheet_task_title
        val navTitleNode = root.findNode {
            it.viewIdResourceName?.endsWith("bottom_sheet_task_title") == true
        } ?: return null

        // 2. DISAMBIGUATION: Dropoff vs Pickup
        // Both use the same ID. We check the text to confirm this is a Dropoff.
        val titleText = navTitleNode.text ?: ""

        // Fail Fast: If it says "Heading to", it implies a Pickup. We want "Deliver to".
        if (titleText.contains("Heading to", ignoreCase = true)) {
            return null
        }

        // Sanity Check: Ensure it actually says "Deliver to"
        if (!titleText.contains("Deliver to", ignoreCase = true)) {
            // It might be some other weird state? Fail safe.
            return null
        }

        // 3. PARSING: Extract & Hash Data

        // Customer Name: "Deliver to John Doe" -> "John Doe"
        val rawName = titleText.replace("Deliver to ", "", ignoreCase = true).trim()

        // Address: Line 1 + Line 2
        val address1 =
            root.findNode { it.viewIdResourceName?.endsWith("bottom_sheet_address_line_1") == true }?.text
        val address2 =
            root.findNode { it.viewIdResourceName?.endsWith("bottom_sheet_address_line_2") == true }?.text

        val rawAddress = listOfNotNull(address1, address2)
            .filter { it.isNotBlank() }
            .joinToString(", ")

        // --- DEBUG LOGGING (Raw Values) ---
        Log.d(tag, "Matched Dropoff Nav. Raw Customer: '$rawName', Raw Address: '$rawAddress'")

        // --- HASHING (Privacy Compliance) ---
        val nameHash = if (rawName.isNotBlank()) UtilityFunctions.generateSha256(rawName) else null
        val addressHash =
            if (rawAddress.isNotBlank()) UtilityFunctions.generateSha256(rawAddress) else null

        // 4. RETURN
        return ScreenInfo.DropoffDetails(
            screen = targetScreen,
            customerNameHash = nameHash,
            addressHash = addressHash,
            status = DropoffStatus.NAVIGATING
        )
    }
}