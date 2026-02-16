package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing

import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode

interface ScreenMatcher {
    val targetScreen: Screen
    val priority: Int

    fun matches(node: UiNode): ScreenInfo?
}