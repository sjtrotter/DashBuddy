package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class DropoffPreArrivalMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.DROPOFF_DETAILS_PRE_ARRIVAL
    override val priority = 8

    override fun matches(node: UiNode): Screen? {
        // Identity: "Deliver to..." header node.
        val hasDeliverToNode = node.findNode {
            it.text?.startsWith("Deliver to", ignoreCase = true) == true
        } != null

        if (!hasDeliverToNode) return null

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

        return if (hasActionButtons || hasContactButtons) targetScreen else null
    }
}