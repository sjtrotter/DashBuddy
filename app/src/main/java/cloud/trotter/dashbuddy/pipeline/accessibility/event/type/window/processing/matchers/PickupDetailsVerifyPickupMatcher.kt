package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class PickupDetailsVerifyPickupMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.PICKUP_DETAILS_VERIFY_PICKUP
    override val priority = 3

    override fun matches(node: UiNode): Screen? {
        val texts = node.allText.joinToString(" | ").lowercase()
        if (!texts.contains("verify order")) return null
        if (!texts.contains("order for")) return null
        if (!texts.contains("confirm pickup")) return null
        if (!texts.contains("can't verify order")) return null
        if (texts.contains("pick up by")) return null  // forbidden
        return targetScreen
    }
}
