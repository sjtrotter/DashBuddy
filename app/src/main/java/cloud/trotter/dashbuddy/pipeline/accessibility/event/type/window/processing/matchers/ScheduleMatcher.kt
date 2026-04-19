package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class ScheduleMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.SCHEDULE_VIEW
    override val priority = 5

    override fun matches(node: UiNode): Screen? {
        val hasTitle = node.findNode {
            it.text.equals("Schedule", ignoreCase = true) &&
                    it.className == "android.widget.TextView"
        } != null

        if (!hasTitle) return null

        val hasAvailableTab = node.findNode {
            it.text.equals("Available", ignoreCase = true)
        } != null

        val hasScheduledTab = node.findNode {
            it.text.equals("Scheduled", ignoreCase = true)
        } != null

        return if (hasAvailableTab || hasScheduledTab) targetScreen else null
    }
}
