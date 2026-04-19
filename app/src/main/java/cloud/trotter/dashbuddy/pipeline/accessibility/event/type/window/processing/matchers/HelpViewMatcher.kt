package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class HelpViewMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.HELP_VIEW
    override val priority = 3

    override fun matches(node: UiNode): Screen? {
        val texts = node.allText.joinToString(" | ").lowercase()
        if (!texts.contains("doordash dasher")) return null
        if (!texts.contains("close webview")) return null
        if (!texts.contains("safety resources")) return null
        if (!texts.contains("additional resources")) return null
        if (!texts.contains("chat with support")) return null
        return targetScreen
    }
}
