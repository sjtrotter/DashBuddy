package cloud.trotter.dashbuddy.pipeline.recognition

import cloud.trotter.dashbuddy.services.accessibility.UiNode
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo

interface ScreenMatcher {
    val targetScreen: Screen
    val priority: Int

    fun matches(node: UiNode): ScreenInfo?
}