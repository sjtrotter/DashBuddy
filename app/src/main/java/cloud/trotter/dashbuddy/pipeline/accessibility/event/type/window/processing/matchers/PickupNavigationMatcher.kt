package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class PickupNavigationMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.NAVIGATION_VIEW_TO_PICK_UP
    override val priority = 10

    override fun matches(node: UiNode): Screen? {
        val navTitleNode = node.findNode {
            it.viewIdResourceName?.endsWith("bottom_sheet_task_title") == true
        } ?: return null

        val titleText = navTitleNode.text ?: ""

        // Fail fast: "Deliver to..." is a dropoff, not a pickup.
        if (titleText.contains("Deliver to", ignoreCase = true)) return null

        // Double-check: if arrive-by node says "Deliver by", it's a dropoff.
        val timeNode = node.findNode {
            it.viewIdResourceName?.endsWith("bottom_sheet_task_arrive_by") == true
        }
        if (timeNode?.text?.contains("Deliver by", ignoreCase = true) == true) return null

        return targetScreen
    }
}