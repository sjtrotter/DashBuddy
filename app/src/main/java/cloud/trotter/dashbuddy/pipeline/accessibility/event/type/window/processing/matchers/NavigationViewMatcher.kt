package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class NavigationViewMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.NAVIGATION_VIEW
    override val priority = 3

    override fun matches(node: UiNode): Screen? {
        val texts = node.allText.joinToString(" | ").lowercase()
        if (!texts.contains("min")) return null
        if (!texts.contains("exit")) return null
        if (listOf("mi", "ft").none { texts.contains(it) }) return null  // someOfTheseTexts
        if (texts.contains("accept")) return null  // forbidden
        if (texts.contains("decline")) return null  // forbidden
        return targetScreen
    }
}
