package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class SetDashEndTimeMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.SET_DASH_END_TIME
    override val priority = 1

    override fun matches(node: UiNode): Screen? {
        // Criterion A: Zone ID node.
        val hasZoneNode = node.findNode {
            it.viewIdResourceName?.endsWith("starting_point_name") == true
        } != null

        // Criterion B: "Select end time" text.
        val hasSelectTimeText = node.findNode {
            it.text?.contains("Select end time", ignoreCase = true) == true
        } != null

        // Criterion C: end_time_description ID (fallback confirmation).
        val hasTimeDescription = node.findNode {
            it.viewIdResourceName?.endsWith("end_time_description") == true
        } != null

        return if (hasZoneNode && (hasSelectTimeText || hasTimeDescription)) targetScreen else null
    }
}