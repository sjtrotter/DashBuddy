package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class AppStartupMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.APP_STARTING_OR_LOADING
    override val priority = 20

    override fun matches(node: UiNode): Screen? {
        val hasStartingText = node.findNode {
            it.text == "Starting…" && it.className?.endsWith("TextView") == true
        } != null

        if (!hasStartingText) return null

        val hasCancelButton = node.findNode {
            it.text == "Cancel" && it.className?.endsWith("Button") == true
        } != null

        return if (hasCancelButton) targetScreen else null
    }
}
