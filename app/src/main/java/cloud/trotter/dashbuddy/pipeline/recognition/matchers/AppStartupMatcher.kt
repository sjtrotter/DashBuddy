package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenMatcher
import cloud.trotter.dashbuddy.state.StateContext

class AppStartupMatcher : ScreenMatcher {

    override val targetScreen = Screen.APP_STARTING_OR_LOADING

    override val priority = 20

    override fun matches(context: StateContext): ScreenInfo? {
        val root = context.rootUiNode ?: return null

        // 1. Check for "Starting..." text
        val hasStartingText = root.findNode {
            it.text == "Starting…" && it.className == "android.widget.TextView"
        } != null

        // Optimization: Fail fast if the main text isn't there
        if (!hasStartingText) return null

        // 2. Check for "Cancel" button
        val hasCancelButton = root.findNode {
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