package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.ParsedTime
import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.order.PickupStatus
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import cloud.trotter.dashbuddy.util.UtilityFunctions
import javax.inject.Inject

class PickupPreArrivalParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.PICKUP_DETAILS_PRE_ARRIVAL

    override fun parse(node: UiNode): ScreenInfo {
        val storeName = node.findNode {
            it.viewIdResourceName?.endsWith("user_name") == true
        }?.text

        val address1 = node.findNode {
            it.viewIdResourceName?.endsWith("address_line_1") == true
        }?.text
        val address2 = node.findNode {
            it.viewIdResourceName?.endsWith("address_line_2") == true
        }?.text

        // If address line 1 is just the store name repeated, drop it.
        val cleanAddress1 = if (address1.equals(storeName, ignoreCase = true)) null else address1

        val fullAddress = listOfNotNull(cleanAddress1, address2)
            .filter { it.isNotBlank() }
            .joinToString(", ")

        // "Pick up by 17:39" — toolbar text node, no resource ID.
        val deadlineText = node.findNode {
            it.text?.startsWith("Pick up by", ignoreCase = true) == true
        }?.text
        val deadline = deadlineText?.let {
            ParsedTime(UtilityFunctions.stripDeadlinePrefix(it), UtilityFunctions.parseDeadlineMillis(it))
        }

        // "4 items" — parse leading integer.
        val itemCount = node.findNode {
            it.viewIdResourceName?.endsWith("items_title_v2") == true
        }?.text?.split(" ")?.firstOrNull()?.toIntOrNull()

        // Status is always NAVIGATING — button click hasn't happened yet.
        return ScreenInfo.PickupDetails(
            screen = targetScreen,
            storeName = storeName,
            storeAddress = fullAddress.ifBlank { null },
            deadline = deadline,
            itemCount = itemCount,
            status = PickupStatus.NAVIGATING
        )
    }
}
