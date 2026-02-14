package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing

import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.LegacyEnumMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenClassifier @Inject constructor(
    // 1. INJECTED: The new, Hilt-managed matchers (OfferMatcher, etc.)
    injectedMatchers: Set<@JvmSuppressWildcards ScreenMatcher>
) {

    // 2. MANUAL: The old enums that still have logic
    private val legacyMatchers = Screen.entries
        .filter { it.hasMatchingCriteria }
        .map { LegacyEnumMatcher(it) }

    // 3. MERGE: Combine both lists, sort by priority
    private val allMatchers = (injectedMatchers + legacyMatchers)
        .sortedByDescending { it.priority }

    fun identify(node: UiNode): ScreenInfo {
        // Iterate through the unified list
        return allMatchers.firstNotNullOfOrNull { it.matches(node) }
            ?: ScreenInfo.Simple(Screen.UNKNOWN)
    }
}