package cloud.trotter.dashbuddy.services.accessibility.screen.matchers

import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenMatcher
import cloud.trotter.dashbuddy.state.StateContext

class ScheduleMatcher : ScreenMatcher {

    override val targetScreen = Screen.SCHEDULE_VIEW

    // Priority 5: Lower than critical overlays (Offers), but specific enough to check before generic fallbacks.
    override val priority = 5

    override fun matches(context: StateContext): ScreenInfo? {
        val root = context.rootUiNode ?: return null

        // 1. Check for the Main Title "Schedule"
        // It appears as a TextView near the top
        val hasTitle = root.findNode {
            it.text.equals("Schedule", ignoreCase = true) &&
                    it.className == "android.widget.TextView"
        } != null

        // 2. Check for the Tabs: "Available" and "Scheduled"
        // These are unique to this view.
        val hasAvailableTab = root.findNode {
            it.text.equals("Available", ignoreCase = true)
        } != null

        val hasScheduledTab = root.findNode {
            it.text.equals("Scheduled", ignoreCase = true)
        } != null

        // 3. Match Logic
        // We require the Title AND at least one of the tabs.
        if (hasTitle && (hasAvailableTab || hasScheduledTab)) {
            return ScreenInfo.Simple(Screen.SCHEDULE_VIEW)
        }

        return null
    }
}