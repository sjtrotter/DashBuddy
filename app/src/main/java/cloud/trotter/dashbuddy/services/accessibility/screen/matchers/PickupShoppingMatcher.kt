package cloud.trotter.dashbuddy.services.accessibility.screen.matchers

import cloud.trotter.dashbuddy.data.event.status.PickupStatus
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenMatcher
import cloud.trotter.dashbuddy.state.StateContext

class PickupShoppingMatcher : ScreenMatcher {

    // Maps to your POST_ARRIVAL_SHOP
    override val targetScreen = Screen.PICKUP_DETAILS_POST_ARRIVAL_SHOP

    // Priority 8: Similar to other Pickup detail screens
    override val priority = 8

    override fun matches(context: StateContext): ScreenInfo? {
        val root = context.rootUiNode ?: return null

        // 1. MATCHING: Main Title "Shop and Deliver"
        // This is a very strong signal.
        val hasTitle = root.findNode {
            it.text.equals("Shop and Deliver", ignoreCase = true)
        } != null

        if (!hasTitle) return null

        // 2. MATCHING: Tabs ("To shop", "Done")
        // We look for the tab layout container or the specific tab texts.
        val hasTabs = root.findNode {
            it.viewIdResourceName?.endsWith("tab_layout") == true
        } != null

        // Alternative Check: If IDs change, check for text "To shop"
        val hasToShopText = root.findNode {
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