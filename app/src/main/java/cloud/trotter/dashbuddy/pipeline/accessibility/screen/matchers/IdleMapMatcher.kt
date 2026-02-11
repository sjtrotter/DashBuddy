package cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers

import cloud.trotter.dashbuddy.data.dash.DashType
import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.Screen
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.ScreenMatcher
import timber.log.Timber
import javax.inject.Inject

class IdleMapMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.MAIN_MAP_IDLE
    override val priority = 1

    override fun matches(node: UiNode): ScreenInfo? {
        // --- 1. NEGATIVE MATCHING (Safety Guards) ---

        // Safety Guard 1: "Return to dash"
        // If this button is visible, we are definitely NOT idle, we are in an active dash
        // (likely navigating menus).
        val isReturnToDash = node.findNode {
            it.text.equals("Return to dash", ignoreCase = true)
        } != null

        if (isReturnToDash) {
            Timber.v("Match failed: 'Return to dash' button detected (Active Dash).")
            return ScreenInfo.Simple(Screen.MAIN_MAP_ON_DASH)
        }

        // --- 2. POSITIVE MATCHING (Structural Fingerprint) ---

        // Criterion A: Earnings/Time Mode Switcher.
        // This is the strongest indicator of "Idle" state. It allows switching between
        // "Earn by Time" and "Earn by Offer", which is impossible during an active dash.
        val hasEarningsSwitcher = node.findNode {
            it.contentDescription == "Earnings Mode Switcher"
        } != null

        // Criterion B: Side Menu.
        // Standard navigation element on the main map.
        val hasSideMenu = node.findNode {
            it.viewIdResourceName?.endsWith("side_nav_compose_view") == true ||
                    it.contentDescription == "Side Menu"
        } != null

        // We removed the brittle check for "Dash" button text.
        // If we have the Switcher + Menu, we are almost certainly on the Idle Map.
        if (!hasEarningsSwitcher || !hasSideMenu) return null

        // --- 3. PARSING LOGIC ---

        // Parse Dash Type (Per Offer vs Earn by Time)
        var dashType: DashType? = null
        if (node.findNode { it.contentDescription == "Time mode off" } != null) {
            dashType = DashType.PER_OFFER
        } else if (node.findNode { it.contentDescription == "Time mode on" } != null) {
            dashType = DashType.BY_TIME
        }

        Timber.v("Parsed DashType: $dashType")

        // Parse Zone Name // -can no longer find zone name in main screen.

        return ScreenInfo.IdleMap(
            screen = Screen.MAIN_MAP_IDLE,
            zoneName = null,
            dashType = dashType
        )
    }
}