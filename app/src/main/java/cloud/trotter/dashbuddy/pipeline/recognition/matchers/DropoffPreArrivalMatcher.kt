package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.data.event.status.DropoffStatus
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenMatcher
import cloud.trotter.dashbuddy.services.accessibility.UiNode
import cloud.trotter.dashbuddy.util.UtilityFunctions

//import cloud.trotter.dashbuddy.log.Logger as Log

class DropoffPreArrivalMatcher : ScreenMatcher {

    override val targetScreen = Screen.DROPOFF_DETAILS_PRE_ARRIVAL
    override val priority = 8

    override fun matches(node: UiNode): ScreenInfo? {
        // 1. PRIMARY MATCHING: "Deliver to..." Header
        // This checks for the main customer card header
        val deliverToNode = node.findNode {
            it.text?.startsWith("Deliver to", ignoreCase = true) == true
        }

        if (deliverToNode == null) return null

        // 2. SECONDARY MATCHING: Action Buttons
        // We look for "Directions", "Continue", or "Complete Delivery"
        val hasActionButtons = node.findNode {
            val txt = it.text
            txt.equals("Directions", true) ||
                    txt.equals("Continue", true) ||
                    txt.equals("Complete Delivery", true)
        } != null

        // We also usually see "Call" or "Message"
        val hasContactButtons = node.findNode {
            it.text.equals("Call", true) || it.text.equals("Message", true)
        } != null

        // Stronger Check: Must have Header + (Action OR Contact button)
        if (!hasActionButtons && !hasContactButtons) {
            return null
        }

        // --- 3. DATA EXTRACTION ---

        // Customer Name: "Deliver to Sam H" -> "Sam H"
        val rawTitle = deliverToNode.text ?: ""
        val rawCustomerName = rawTitle.replace("Deliver to", "", ignoreCase = true).trim()

        val customerHash = if (rawCustomerName.isNotBlank()) {
            UtilityFunctions.generateSha256(rawCustomerName)
        } else null

        // Status Determination
        val status = if (node.findNode { it.text.equals("Directions", true) } != null) {
            DropoffStatus.NAVIGATING
        } else if (node.findNode {
                it.text.equals(
                    "Continue",
                    true
                ) || it.text.equals("Complete Delivery", true)
            } != null) {
            DropoffStatus.ARRIVED
        } else {
            DropoffStatus.UNKNOWN
        }

        return ScreenInfo.DropoffDetails(
            screen = targetScreen,
            customerNameHash = customerHash,
            addressHash = null, // Address is unstable without IDs, skipping for now
            status = status
        )
    }
}