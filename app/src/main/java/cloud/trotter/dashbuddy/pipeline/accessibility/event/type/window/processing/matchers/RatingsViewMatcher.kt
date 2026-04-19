package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class RatingsViewMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.RATINGS_VIEW
    override val priority = 3

    override fun matches(node: UiNode): Screen? {
        val texts = node.allText.joinToString(" | ").lowercase()
        if (!texts.contains("ratings")) return null
        if (!texts.contains("acceptance rate")) return null
        if (!texts.contains("completion rate")) return null
        if (!texts.contains("overall")) return null
        return targetScreen
    }
}
