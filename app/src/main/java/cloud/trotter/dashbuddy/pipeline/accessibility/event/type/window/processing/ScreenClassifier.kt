package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenClassifier @Inject constructor(
    injectedMatchers: Set<@JvmSuppressWildcards ScreenMatcher>,
    injectedParsers: Set<@JvmSuppressWildcards ScreenParser>
) {

    private val allMatchers = injectedMatchers
        .sortedByDescending { it.priority }

    private val parserMap: Map<Screen, ScreenParser> =
        injectedParsers.associateBy { it.targetScreen }

    fun identify(node: UiNode): ScreenInfo {
        val screen = allMatchers.firstNotNullOfOrNull { it.matches(node) }
            ?: return ScreenInfo.Simple(Screen.UNKNOWN)
        return parserMap[screen]?.parse(node) ?: ScreenInfo.Simple(screen)
    }
}
