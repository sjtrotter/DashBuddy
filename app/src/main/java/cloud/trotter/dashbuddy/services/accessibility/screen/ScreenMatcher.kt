package cloud.trotter.dashbuddy.services.accessibility.screen

import cloud.trotter.dashbuddy.state.StateContext

interface ScreenMatcher {
    // Which screen enum does this matcher identify?
    val targetScreen: Screen

    // Priority allows specific screens (like Popups) to be checked before generic ones (like Map)
    val priority: Int get() = 0

    // The logic: Returns true if the context matches this screen
    fun matches(context: StateContext): ScreenInfo?
}