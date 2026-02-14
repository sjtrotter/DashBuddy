package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.data.event.status.PickupStatus
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.Screen
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import javax.inject.Inject

class PickupShoppingMatcher @Inject constructor() : ScreenMatcher {

    // Maps to your POST_ARRIVAL_SHOP
    override val targetScreen = Screen.PICKUP_DETAILS_POST_ARRIVAL_SHOP

    // Priority 8: Similar to other Pickup detail screens
    override val priority = 8

    override fun matches(node: UiNode): ScreenInfo? {
        // 1. MATCHING: Main Title "Shop and Deliver"
        // This is a very strong signal.
        val hasTitle = node.findNode {
            it.text.equals("Shop and Deliver", ignoreCase = true)
        } != null

        if (!hasTitle) return null

        // 2. MATCHING: Tabs ("To shop", "Done")
        // We look for the tab layout container or the specific tab texts.
        val hasTabs = node.findNode {
            it.viewIdResourceName?.endsWith("tab_layout") == true
        } != null

        // Alternative Check: If IDs change, check for text "To shop"
        val hasToShopText = node.findNode {
            it.text?.startsWith("To shop", ignoreCase = true) == true
        } != null

        if (!hasTabs && !hasToShopText) return null

        // 3. RETURN: We are definitely shopping.
        // Note: Store Name is NOT available on this screen (as you noted).
        // The Handler will use "Sticky" logic to keep the previous store name.
        return ScreenInfo.PickupDetails(
            screen = targetScreen,
            storeName = null,
            status = PickupStatus.SHOPPING
        )
    }
}