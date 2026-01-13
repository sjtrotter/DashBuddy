package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.pipeline.recognition.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenMatcher
import cloud.trotter.dashbuddy.services.accessibility.UiNode

class AppStartupMatcher : ScreenMatcher {

    override val targetScreen = Screen.APP_STARTING_OR_LOADING

    override val priority = 20

    override fun matches(node: UiNode): ScreenInfo? {

        // 1. Check for "Starting..." text
        val hasStartingText = node.findNode {
            it.text == "Starting…" && it.className == "android.widget.TextView"
        } != null

        // Optimization: Fail fast if the main text isn't there
        if (!hasStartingText) return null

        // 2. Check for "Cancel" button
        val hasCancelButton = node.findNode {
            it.text == "Cancel" && it.className == "android.widget.Button"
        } != null

        // 3. Return Result
        return if (hasCancelButton) {
            ScreenInfo.Simple(Screen.APP_STARTING_OR_LOADING)
        } else {
            null
        }
    }
}