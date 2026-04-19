package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class SafetyViewMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.SAFETY_VIEW
    override val priority = 3

    override fun matches(node: UiNode): Screen? {
        val texts = node.allText.joinToString(" | ").lowercase()
        if (!texts.contains("safedash")) return null
        if (!texts.contains("safety tools")) return null
        if (!texts.contains("report safety issue")) return null
        if (!texts.contains("share your location")) return null
        return targetScreen
    }
}
