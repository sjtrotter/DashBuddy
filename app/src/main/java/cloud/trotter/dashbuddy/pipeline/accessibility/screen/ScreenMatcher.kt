package cloud.trotter.dashbuddy.pipeline.accessibility.screen

import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode

interface ScreenMatcher {
    val targetScreen: Screen
    val priority: Int

    fun matches(node: UiNode): ScreenInfo?
}