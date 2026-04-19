package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class PickupPreArrivalMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.PICKUP_DETAILS_PRE_ARRIVAL
    override val priority = 8

    override fun matches(node: UiNode): Screen? {
        // Identity signal: "Pickup from" label (not a dropoff).
        val hasPickupFromLabel = node.findNode {
            it.viewIdResourceName?.endsWith("user_name_label") == true &&
                    it.text?.contains("Pickup from", true) == true
        } != null

        if (!hasPickupFromLabel) return null

        // Button must say "Directions" or "Arrived at store".
        val buttonText = node.findNode {
            it.viewIdResourceName?.endsWith("textView_prism_button_title") == true
        }?.text ?: ""

        val isPreArrival = buttonText.equals("Directions", true) ||
                buttonText.equals("Arrived at store", true)

        return if (isPreArrival) targetScreen else null
    }
}