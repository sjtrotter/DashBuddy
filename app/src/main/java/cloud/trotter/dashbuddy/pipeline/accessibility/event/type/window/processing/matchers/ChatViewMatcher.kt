package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class ChatViewMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.CHAT_VIEW
    override val priority = 3

    override fun matches(node: UiNode): Screen? {
        val texts = node.allText.joinToString(" | ").lowercase()
        if (!texts.contains("dasher")) return null
        if (!texts.contains("messages")) return null
        if (texts.contains("folder")) return null  // forbidden
        return targetScreen
    }
}
