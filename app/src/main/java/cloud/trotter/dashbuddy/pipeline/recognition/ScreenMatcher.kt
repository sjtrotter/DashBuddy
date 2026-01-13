package cloud.trotter.dashbuddy.pipeline.recognition

import cloud.trotter.dashbuddy.services.accessibility.UiNode

interface ScreenMatcher {
    val targetScreen: Screen
    val priority: Int

    fun matches(node: UiNode): ScreenInfo?
}