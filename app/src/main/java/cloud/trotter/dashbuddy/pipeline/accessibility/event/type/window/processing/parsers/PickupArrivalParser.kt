package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.ParsedTime
import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.order.PickupStatus
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import cloud.trotter.dashbuddy.util.UtilityFunctions
import timber.log.Timber
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

        // "Pick up by 19:12" — toolbar text node, no resource ID.
        val deadlineText = node.findNode {
            it.text?.startsWith("Pick up by", ignoreCase = true) == true
        }?.text
        val deadline = deadlineText?.let { ParsedTime(it, UtilityFunctions.parseDeadlineMillis(it)) }

        // "5 items" — parse leading integer.
        val itemCount = node.findNode {
            it.viewIdResourceName?.endsWith("items_title_v2") == true
        }?.text?.split(" ")?.firstOrNull()?.toIntOrNull()

        // "Total amount of this order is $23.95" — present only for Red Card orders.
        val redCardText = node.findNode {
            it.viewIdResourceName?.endsWith("banner_label") == true
        }?.text
        val redCardTotal = UtilityFunctions.parseCurrency(redCardText)
        Timber.d("PickupArrival: deadline='$deadlineText', items=$itemCount, redCard=$redCardTotal")

        return ScreenInfo.PickupDetails(
            screen = targetScreen,
            storeName = storeNameCandidate,
            customerNameHash = customerHash,
            deadline = deadline,
            itemCount = itemCount,
            redCardTotal = redCardTotal,
            status = PickupStatus.ARRIVED
        )
    }
}
