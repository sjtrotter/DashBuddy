package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.order.PickupStatus
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import javax.inject.Inject

class PickupNavigationParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.NAVIGATION_VIEW_TO_PICK_UP

    override fun parse(node: UiNode): ScreenInfo {
        val titleText = node.findNode {
            it.viewIdResourceName?.endsWith("bottom_sheet_task_title") == true
        }?.text ?: ""

        // "Heading to McDonald's" -> "McDonald's"
        val storeName = titleText.replace("Heading to ", "", ignoreCase = true).trim()

        val address1 = node.findNode {
            it.viewIdResourceName?.endsWith("bottom_sheet_address_line_1") == true
        }?.text
        val address2 = node.findNode {
            it.viewIdResourceName?.endsWith("bottom_sheet_address_line_2") == true
        }?.text

        val fullAddress = listOfNotNull(address1, address2)
            .filter { it.isNotBlank() }
            .joinToString(", ")

        return ScreenInfo.PickupDetails(
            screen = targetScreen,
            storeName = storeName.ifBlank { null },
            storeAddress = fullAddress.ifBlank { null },
            status = PickupStatus.NAVIGATING
        )
    }
}
