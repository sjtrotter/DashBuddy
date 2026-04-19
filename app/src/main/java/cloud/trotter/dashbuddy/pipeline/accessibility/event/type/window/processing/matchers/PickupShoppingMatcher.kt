package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class PickupShoppingMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.PICKUP_DETAILS_POST_ARRIVAL_SHOP
    override val priority = 8

    override fun matches(node: UiNode): Screen? {
        val hasTitle = node.findNode { it.text.equals("Shop and Deliver", ignoreCase = true) } != null
        if (!hasTitle) return null

        val hasTabs = node.findNode { it.viewIdResourceName?.endsWith("tab_layout") == true } != null
        val hasToShopText = node.findNode { it.text?.startsWith("To shop", ignoreCase = true) == true } != null

        return if (hasTabs || hasToShopText) targetScreen else null
    }
}