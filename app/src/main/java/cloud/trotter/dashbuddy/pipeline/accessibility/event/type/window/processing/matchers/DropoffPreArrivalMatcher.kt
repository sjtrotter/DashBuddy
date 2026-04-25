package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class DropoffPreArrivalMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.DROPOFF_DETAILS_PRE_ARRIVAL
    override val priority = 8

    override fun matches(node: UiNode): Screen? {
        // Identity: "Deliver to..." or "Heading to..." header node.
        // Shop & Deliver uses "Heading to [customer]" on the pre-arrival screen.
        val titleNode = node.findNode {
            it.text?.startsWith("Deliver to", ignoreCase = true) == true ||
                it.text?.startsWith("Heading to", ignoreCase = true) == true
        } ?: return null

        val isDeliverTo = titleNode.text?.startsWith("Deliver to", ignoreCase = true) == true

        // Must have action or contact buttons to confirm this is the delivery details screen.
        val hasActionButtons = node.findNode {
            val txt = it.text
            txt.equals("Directions", true) ||
                    txt.equals("Continue", true) ||
                    txt.equals("Complete Delivery", true)
        } != null

        val hasContactButtons = node.findNode {
            it.text.equals("Call", true) || it.text.equals("Message", true)
        } != null

        // "Heading to" + "Directions" alone is ambiguous — pickup navigation screens also show
        // this combination. Require delivery-specific buttons (Call/Message/Continue/Complete)
        // to confirm it's a customer-facing pre-arrival screen, not a store navigation screen.
        if (!isDeliverTo && !hasContactButtons) {
            val hasDeliverySpecificAction = node.findNode {
                it.text.equals("Continue", true) || it.text.equals("Complete Delivery", true)
            } != null
            if (!hasDeliverySpecificAction) return null
        }

        return if (hasActionButtons || hasContactButtons) targetScreen else null
    }
}