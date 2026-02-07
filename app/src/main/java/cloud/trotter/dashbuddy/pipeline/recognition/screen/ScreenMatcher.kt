package cloud.trotter.dashbuddy.pipeline.recognition.screen

import cloud.trotter.dashbuddy.pipeline.model.UiNode

interface ScreenMatcher {
    val targetScreen: Screen
    val priority: Int

    fun matches(node: UiNode): ScreenInfo?
}