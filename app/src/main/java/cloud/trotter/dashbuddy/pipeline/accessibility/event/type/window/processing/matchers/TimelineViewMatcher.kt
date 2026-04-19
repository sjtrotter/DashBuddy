package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class TimelineViewMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.TIMELINE_VIEW
    override val priority = 3

    override fun matches(node: UiNode): Screen? {
        val texts = node.allText.joinToString(" | ").lowercase()
        if (!texts.contains("current dash")) return null
        if (!texts.contains("pause orders")) return null
        if (!texts.contains("end now")) return null
        if (!texts.contains("dash ends at")) return null
        if (!texts.contains("add time")) return null
        return targetScreen
    }
}
