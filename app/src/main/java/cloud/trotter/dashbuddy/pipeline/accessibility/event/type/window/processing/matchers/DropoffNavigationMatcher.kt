package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class DropoffNavigationMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.NAVIGATION_VIEW_TO_DROP_OFF
    override val priority = 10

    override fun matches(node: UiNode): Screen? {
        val navTitleNode = node.findNode {
            it.viewIdResourceName?.endsWith("bottom_sheet_task_title") == true
        } ?: return null

        val titleText = navTitleNode.text ?: ""

        // "Deliver to [name]" — definitively a dropoff.
        if (titleText.contains("Deliver to", ignoreCase = true)) return targetScreen

        // "Heading to [name]" is shared with pickup navigation. Confirm dropoff via "Deliver by"
        // in the arrive-by node — pickup navigation says "Pick up by [time]" instead.
        if (titleText.contains("Heading to", ignoreCase = true)) {
            val timeNode = node.findNode {
                it.viewIdResourceName?.endsWith("bottom_sheet_task_arrive_by") == true
            }
            if (timeNode?.text?.contains("Deliver by", ignoreCase = true) == true) return targetScreen
        }

        return null
    }
}