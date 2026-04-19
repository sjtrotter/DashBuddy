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

        // Fail fast: "Heading to" means pickup, not dropoff.
        if (titleText.contains("Heading to", ignoreCase = true)) return null

        // Must say "Deliver to" to be a dropoff.
        return if (titleText.contains("Deliver to", ignoreCase = true)) targetScreen else null
    }
}