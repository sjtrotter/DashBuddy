package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class EarningsViewMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.EARNINGS_VIEW
    override val priority = 3

    override fun matches(node: UiNode): Screen? {
        val texts = node.allText.joinToString(" | ").lowercase()
        if (!texts.contains("earnings")) return null
        if (!texts.contains("this week")) return null
        if (listOf("past weeks", "balance", "crimson").none { texts.contains(it) }) return null
        return targetScreen
    }
}
