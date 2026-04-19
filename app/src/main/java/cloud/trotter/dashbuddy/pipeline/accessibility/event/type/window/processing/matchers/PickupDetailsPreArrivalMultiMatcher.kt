package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class PickupDetailsPreArrivalMultiMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.PICKUP_DETAILS_PRE_ARRIVAL_PICKUP_MULTI
    override val priority = 3

    override fun matches(node: UiNode): Screen? {
        val texts = node.allText.joinToString(" | ").lowercase()
        if (!texts.contains("confirm at store")) return null
        if (!texts.contains("orders")) return null
        if (!texts.contains("you have")) return null
        if (!texts.contains("orders to pick up at")) return null
        if (!texts.contains("pick up each one to continue")) return null
        return targetScreen
    }
}
