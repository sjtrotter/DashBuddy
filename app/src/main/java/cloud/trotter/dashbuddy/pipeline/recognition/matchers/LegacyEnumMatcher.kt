package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenMatcher
import cloud.trotter.dashbuddy.state.StateContext

/**
 * A wrapper that lets us use the old Screen Enum logic inside the new system.
 */
class LegacyEnumMatcher(
    override val targetScreen: Screen,
    override val priority: Int = 0
) : ScreenMatcher {

    override fun matches(context: StateContext): ScreenInfo? {
        if (!targetScreen.matches(context)) return null
        return ScreenInfo.Simple(targetScreen)
    }
}