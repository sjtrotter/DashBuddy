package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.order.PickupStatus
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import cloud.trotter.dashbuddy.util.UtilityFunctions
import javax.inject.Inject

class PickupArrivalParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE

    override fun parse(node: UiNode): ScreenInfo {
        val rawCustomerName = node.findNode {
            it.viewIdResourceName?.endsWith("customer_name") == true
        }?.text
        val customerHash = if (!rawCustomerName.isNullOrBlank()) {
            UtilityFunctions.generateSha256(rawCustomerName)
        } else null

        // instructions_title can be the store name or "Parking instructions" / "Notes".
        val rawTitle = node.findNode {
            it.viewIdResourceName?.endsWith("instructions_title") == true
        }?.text
        val storeNameCandidate = if (
            !rawTitle.isNullOrBlank() &&
            !rawTitle.contains("instructions", true) &&
            !rawTitle.contains("notes", true)
        ) rawTitle else null

        return ScreenInfo.PickupDetails(
            screen = targetScreen,
            storeName = storeNameCandidate,
            customerNameHash = customerHash,
            status = PickupStatus.ARRIVED
        )
    }
}
