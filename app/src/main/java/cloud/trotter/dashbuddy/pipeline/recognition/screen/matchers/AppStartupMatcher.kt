package cloud.trotter.dashbuddy.pipeline.recognition.screen.matchers

import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.screen.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenMatcher
import javax.inject.Inject

class AppStartupMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.APP_STARTING_OR_LOADING

    override val priority = 20

    override fun matches(node: UiNode): ScreenInfo? {

        // 1. Check for "Starting..." text
        val hasStartingText = node.findNode {
            it.text == "Starting…" && it.className?.endsWith("TextView") == true
        } != null

        // Optimization: Fail fast if the main text isn't there
        if (!hasStartingText) return null

        // 2. Check for "Cancel" button
        val hasCancelButton = node.findNode {
            it.text == "Cancel" && it.className?.endsWith("Button") == true
        } != null

        // 3. Return Result
        return if (hasCancelButton) {
            ScreenInfo.Simple(Screen.APP_STARTING_OR_LOADING)
        } else {
            null
        }
    }
}