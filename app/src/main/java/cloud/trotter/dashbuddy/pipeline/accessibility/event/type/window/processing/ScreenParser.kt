package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

/**
 * Extracts structured [ScreenInfo] data from a [UiNode] tree that has already
 * been confirmed by its corresponding [ScreenMatcher].
 *
 * Called by [ScreenClassifier] only after [ScreenMatcher.matches] returns non-null.
 * Screens with no meaningful data to extract (those returning [ScreenInfo.Simple])
 * do not need a parser — the classifier falls back to [ScreenInfo.Simple] automatically.
 */
interface ScreenParser {
    /** The screen this parser extracts data for. Must match the paired [ScreenMatcher.targetScreen]. */
    val targetScreen: Screen

    /** Extract structured data from a confirmed [UiNode] tree. Must not return null. */
    fun parse(node: UiNode): ScreenInfo
}
