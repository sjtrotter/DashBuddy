package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class DashSummaryMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.DASH_SUMMARY_SCREEN
    override val priority = 1

    override fun matches(node: UiNode): Screen? {
        val hasTitle = node.hasNode { it.text.equals("Dash summary", ignoreCase = true) }
        val hasDoneButton = node.hasNode {
            it.hasId("textView_prism_button_title") && it.text.equals("Done", ignoreCase = true)
        }
        return if (hasTitle && hasDoneButton) targetScreen else null
    }
}