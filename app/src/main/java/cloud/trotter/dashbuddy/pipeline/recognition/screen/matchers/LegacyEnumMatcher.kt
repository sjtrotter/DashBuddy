package cloud.trotter.dashbuddy.pipeline.recognition.screen.matchers

import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.screen.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenMatcher

/**
 * A wrapper that lets us use the old Screen Enum logic inside the new system.
 */
class LegacyEnumMatcher(
    override val targetScreen: Screen,
    override val priority: Int = 0
) : ScreenMatcher {

    override fun matches(node: UiNode): ScreenInfo? {
        if (!targetScreen.matches(node)) return null
        return ScreenInfo.Simple(targetScreen)
    }
}