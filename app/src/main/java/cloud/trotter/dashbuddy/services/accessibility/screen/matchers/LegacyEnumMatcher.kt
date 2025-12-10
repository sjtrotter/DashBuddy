package cloud.trotter.dashbuddy.services.accessibility.screen.matchers

import cloud.trotter.dashbuddy.data.offer.OfferParser
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
        // 1. Use the old logic in Screen.kt to check matching
        if (!targetScreen.matches(context)) return null

        // 2. Handle parsers for legacy screens that need them
        return when {

            targetScreen == Screen.OFFER_POPUP -> {
                val parsedOffer = OfferParser.parseOffer(context.rootNodeTexts)
                parsedOffer?.let { ScreenInfo.Offer(targetScreen, it) }
                    ?: ScreenInfo.Simple(targetScreen) // Fallback
            }

            else -> {
                // For any other screen, just return the simple type
                ScreenInfo.Simple(targetScreen)
            }
        }
    }
}