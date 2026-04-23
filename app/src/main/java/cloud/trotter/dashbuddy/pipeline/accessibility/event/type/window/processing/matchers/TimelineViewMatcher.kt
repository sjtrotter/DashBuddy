package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class TimelineViewMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.TIMELINE_VIEW
    override val priority = 25

    override fun matches(node: UiNode): Screen? {
        val texts = node.allText.joinToString(" | ").lowercase()
        if (!texts.contains("dash ends at")) return null
        if (!texts.contains("pause orders")) return null
        return targetScreen
    }
}
