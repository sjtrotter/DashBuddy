package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.ParsedTime
import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.order.DropoffStatus
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import cloud.trotter.dashbuddy.util.UtilityFunctions
import timber.log.Timber
import javax.inject.Inject

class DropoffNavigationParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.NAVIGATION_VIEW_TO_DROP_OFF

    override fun parse(node: UiNode): ScreenInfo {
        val titleText = node.findNode {
            it.viewIdResourceName?.endsWith("bottom_sheet_task_title") == true
        }?.text ?: ""

        // "Deliver to John Doe" or "Heading to John Doe" -> "John Doe"
        val rawName = titleText
            .replace("Deliver to ", "", ignoreCase = true)
            .replace("Heading to ", "", ignoreCase = true)
            .trim()

        val address1 = node.findNode {
            it.viewIdResourceName?.endsWith("bottom_sheet_address_line_1") == true
        }?.text
        val address2 = node.findNode {
            it.viewIdResourceName?.endsWith("bottom_sheet_address_line_2") == true
        }?.text

        val rawAddress = listOfNotNull(address1, address2)
            .filter { it.isNotBlank() }
            .joinToString(", ")

        val deadlineText = node.findNode {
            it.viewIdResourceName?.endsWith("bottom_sheet_task_arrive_by") == true
        }?.text
        val deadline = deadlineText?.let { ParsedTime(it, UtilityFunctions.parseDeadlineMillis(it)) }

        Timber.d("DropoffNav: raw customer='$rawName', raw address='$rawAddress', deadline='$deadlineText'")

        val nameHash = if (rawName.isNotBlank()) UtilityFunctions.generateSha256(rawName) else null
        val addressHash = if (rawAddress.isNotBlank()) UtilityFunctions.generateSha256(rawAddress) else null

        return ScreenInfo.DropoffDetails(
            screen = targetScreen,
            customerNameHash = nameHash,
            addressHash = addressHash,
            deadline = deadline,
            status = DropoffStatus.NAVIGATING
        )
    }
}
