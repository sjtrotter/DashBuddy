package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.order.DropoffStatus
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import cloud.trotter.dashbuddy.util.UtilityFunctions
import javax.inject.Inject

class DropoffPreArrivalParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.DROPOFF_DETAILS_PRE_ARRIVAL

    override fun parse(node: UiNode): ScreenInfo {
        val deliverToNode = node.findNode {
            it.text?.startsWith("Deliver to", ignoreCase = true) == true
        }
        val rawTitle = deliverToNode?.text ?: ""
        val rawCustomerName = rawTitle.replace("Deliver to", "", ignoreCase = true).trim()
        val customerHash = if (rawCustomerName.isNotBlank()) {
            UtilityFunctions.generateSha256(rawCustomerName)
        } else null

        val status = when {
            node.findNode { it.text.equals("Directions", true) } != null -> DropoffStatus.NAVIGATING
            node.findNode {
                it.text.equals("Continue", true) || it.text.equals("Complete Delivery", true)
            } != null -> DropoffStatus.ARRIVED
            else -> DropoffStatus.UNKNOWN
        }

        return ScreenInfo.DropoffDetails(
            screen = targetScreen,
            customerNameHash = customerHash,
            addressHash = null,
            status = status
        )
    }
}
