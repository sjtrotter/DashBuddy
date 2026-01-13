package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.data.dash.DashType
import cloud.trotter.dashbuddy.pipeline.recognition.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenMatcher
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.log.Logger as Log

class IdleMapMatcher : ScreenMatcher {

    private val tag = "IdleMapMatcher"

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
            Log.v(tag, "Match failed: 'Return to dash' button detected (Active Dash).")
            return null
        }

        // --- 2. POSITIVE MATCHING (Structural Fingerprint) ---

        // Criterion A: Earnings/Time Mode Switcher.
        // This is the strongest indicator of "Idle" state. It allows switching between
        // "Earn by Time" and "Earn by Offer", which is impossible during an active dash.
        val hasEarningsSwitcher = node.findNode {
            it.contentDescription == "Earnings Mode Switcher"
        } != null

        Log.v(tag, "Criterion A (Has Earnings Switcher): $hasEarningsSwitcher")

        // Criterion B: Side Menu.
        // Standard navigation element on the main map.
        val hasSideMenu = node.findNode {
            it.viewIdResourceName?.endsWith("side_nav_compose_view") == true ||
                    it.contentDescription == "Side Menu"
        } != null

        Log.v(tag, "Criterion B (Has Side Menu): $hasSideMenu")

        // We removed the brittle check for "Dash" button text.
        // If we have the Switcher + Menu, we are almost certainly on the Idle Map.
        if (!hasEarningsSwitcher || !hasSideMenu) {
            Log.d(tag, "Match failed. Missing structural elements.")
            return null
        }

        // --- 3. PARSING LOGIC ---
        Log.d(tag, "Match success! Parsing dash details...")

        // Parse Dash Type (Per Offer vs Earn by Time)
        var dashType: DashType? = null
        if (node.findNode { it.contentDescription == "Time mode off" } != null) {
            dashType = DashType.PER_OFFER
        } else if (node.findNode { it.contentDescription == "Time mode on" } != null) {
            dashType = DashType.BY_TIME
        }

        Log.v(tag, "Parsed DashType: $dashType")

        // Parse Zone Name
        // Strategy: Find the ScrollView at the top.
        // We filter out common UI prompts to find the actual location text.
        val scrollView = node.findNode { it.className == "android.widget.ScrollView" }

        val zoneName = scrollView?.children?.firstOrNull {
            it.className == "android.widget.TextView" &&
                    !it.text.isNullOrBlank() &&
                    it.text != "Schedule your dash for later" && !it.text.contains(
                "full right now",
                ignoreCase = true
            ) && !it.text.contains("Start your scheduled dash", ignoreCase = true)
        }?.text

        Log.v(tag, "Parsed ZoneName: '$zoneName'")

        return ScreenInfo.IdleMap(
            screen = Screen.MAIN_MAP_IDLE,
            zoneName = zoneName,
            dashType = dashType
        )
    }
}