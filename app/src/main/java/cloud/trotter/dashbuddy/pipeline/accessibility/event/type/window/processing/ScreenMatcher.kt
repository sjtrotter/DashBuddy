package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

interface ScreenMatcher {
    val targetScreen: Screen
    val priority: Int

    fun matches(node: UiNode): ScreenInfo?
}