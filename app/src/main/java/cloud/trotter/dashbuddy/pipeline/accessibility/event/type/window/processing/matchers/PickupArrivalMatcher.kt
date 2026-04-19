package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class PickupArrivalMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE
    override val priority = 8

    override fun matches(node: UiNode): Screen? {
        // Identity: "Order for" label with customer_name_label ID.
        val hasOrderForLabel = node.findNode {
            it.viewIdResourceName?.endsWith("customer_name_label") == true &&
                    it.text?.contains("Order for", true) == true
        } != null

        if (!hasOrderForLabel) return null

        // Confirmation: button says "Confirm", "Continue", or "Start".
        val buttonText = node.findNode {
            it.viewIdResourceName?.endsWith("textView_prism_button_title") == true
        }?.text ?: ""

        val isConfirmScreen = buttonText.contains("Confirm", true) ||
                buttonText.contains("Continue", true) ||
                buttonText.contains("Start", true)

        return if (isConfirmScreen) targetScreen else null
    }
}