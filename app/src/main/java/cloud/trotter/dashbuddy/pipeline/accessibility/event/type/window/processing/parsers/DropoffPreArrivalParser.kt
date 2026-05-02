package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.ParsedTime
import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import cloud.trotter.dashbuddy.util.UtilityFunctions
import timber.log.Timber
import javax.inject.Inject

class DropoffPreArrivalParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.DROPOFF_DETAILS_PRE_ARRIVAL

    override fun parse(node: UiNode): ParsedFields {
        val deliverToNode = node.findNode {
            it.text?.startsWith("Deliver to", ignoreCase = true) == true ||
                it.text?.startsWith("Heading to", ignoreCase = true) == true
        }
        val rawTitle = deliverToNode?.text ?: ""
        val rawCustomerName = rawTitle
            .replace("Deliver to", "", ignoreCase = true)
            .replace("Heading to", "", ignoreCase = true)
            .trim()
        val customerHash = if (rawCustomerName.isNotBlank()) {
            UtilityFunctions.generateSha256(rawCustomerName)
        } else null

        // "by 6:10 PM" — standalone text node immediately after the customer name.
        val deadlineText = node.findNode {
            it.text?.startsWith("by ", ignoreCase = true) == true &&
                it.text?.contains(Regex("\\d{1,2}:\\d{2}")) == true
        }?.text
        val deadline = deadlineText?.let {
            ParsedTime(UtilityFunctions.stripDeadlinePrefix(it), UtilityFunctions.parseDeadlineMillis(it))
        }

        // Address: two consecutive ID-less text nodes — first matches a street number pattern,
        // second ends with a 5-digit zip code. Not always present on this screen.
        val streetRegex = Regex("^\\d{1,5}\\s+\\S")
        val zipRegex = Regex("\\d{5}$")
        val allTexts = node.findNodes { it.text != null && it.viewIdResourceName == null }
        val addr1Idx = allTexts.indexOfFirst { streetRegex.containsMatchIn(it.text.orEmpty()) }
        val addr1 = allTexts.getOrNull(addr1Idx)?.text
        val addr2 = allTexts.getOrNull(addr1Idx + 1)?.text?.takeIf { zipRegex.containsMatchIn(it) }
        val rawAddress = listOfNotNull(addr1, addr2).filter { it.isNotBlank() }.joinToString(", ")
        val addressHash = rawAddress.ifBlank { null }?.let { UtilityFunctions.generateSha256(it) }

        // Determine subFlow from button text.
        val subFlow = when {
            node.findNode {
                it.text.equals("Continue", true) || it.text.equals("Complete Delivery", true)
            } != null -> TaskSubFlow.ARRIVED
            else -> TaskSubFlow.NAVIGATION
        }

        Timber.d("DropoffPreArrival: deadline='$deadlineText', address='$rawAddress', subFlow=$subFlow")

        return ParsedFields.TaskFields(
            phase = TaskPhase.DROPOFF,
            subFlow = subFlow,
            customerNameHash = customerHash,
            customerAddressHash = addressHash,
            deadline = deadline,
        )
    }
}
