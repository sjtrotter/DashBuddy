package cloud.trotter.dashbuddy.services.accessibility.screen.matchers

import cloud.trotter.dashbuddy.data.dash.DashType
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenMatcher
import cloud.trotter.dashbuddy.state.StateContext

class IdleMapMatcher : ScreenMatcher {

    override val targetScreen = Screen.MAIN_MAP_IDLE

    // Priority 1: Check this after high-priority screens (Offers, Nav),
    // but before generic fallbacks.
    override val priority = 1

    override fun matches(context: StateContext): ScreenInfo? {
        val root = context.rootUiNode ?: return null

        // --- 1. MATCHING LOGIC ---

        // Criterion A: Must have the "Dash" button.
        // This distinguishes the map from the "Drawer" or "Schedule" screens.
        val hasDashButton = root.findNode {
            it.text.equals("Dash", ignoreCase = true)
        } != null

        // Criterion B: Must have standard map/nav elements.
        // We check for the Side Menu (ID is preferred, Desc is fallback)
        val hasSideMenu = root.findNode {
            it.viewIdResourceName?.endsWith("side_nav_compose_view") == true ||
                    it.contentDescription == "Side Menu"
        } != null

        // Criterion C: Earnings/Time Mode Switcher must be present.
        // This ensures the screen is fully loaded and we can parse the Dash Type.
        val hasEarningsSwitcher = root.findNode {
            it.contentDescription == "Earnings Mode Switcher"
        } != null

        // If we don't have these core elements, we aren't on the Idle Map.
        if (!hasDashButton || !hasSideMenu || !hasEarningsSwitcher) {
            return null
        }

        // --- 2. PARSING LOGIC ---

        // Parse Dash Type (Per Offer vs Earn by Time)
        var dashType: DashType? = null
        if (root.findNode { it.contentDescription == "Time mode off" } != null) {
            dashType = DashType.PER_OFFER
        } else if (root.findNode { it.contentDescription == "Time mode on" } != null) {
            dashType = DashType.BY_TIME
        }

        // Parse Zone Name
        // Strategy: Find the ScrollView at the top, grab the first text inside it.
        // (Based on your logs: ScrollView -> TextView("TX: North Central"))
        val scrollView = root.findNode { it.className == "android.widget.ScrollView" }

        val zoneName = scrollView?.children?.firstOrNull {
            it.className == "android.widget.TextView" && !it.text.isNullOrBlank()
        }?.text

        // --- 3. RETURN RESULT ---
        return ScreenInfo.IdleMap(
            screen = Screen.MAIN_MAP_IDLE,
            zoneName = zoneName,
            dashType = dashType
        )
    }
}