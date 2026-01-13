package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.pipeline.recognition.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenMatcher
import cloud.trotter.dashbuddy.services.accessibility.UiNode

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