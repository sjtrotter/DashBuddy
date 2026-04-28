package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing

import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.rules.JsonRuleInterpreter
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenClassifier @Inject constructor(
    injectedMatchers: Set<@JvmSuppressWildcards ScreenMatcher>,
    injectedParsers: Set<@JvmSuppressWildcards ScreenParser>,
    private val interpreter: JsonRuleInterpreter,
) {

    private val allMatchers = injectedMatchers.sortedByDescending { it.priority }

    private val parserMap: Map<Screen, ScreenParser> =
        injectedParsers.associateBy { it.targetScreen }

    fun identify(node: UiNode): ScreenInfo {
        // --- Kotlin matchers: authoritative ---
        val screen = allMatchers.firstNotNullOfOrNull { it.matches(node) }
            ?: run {
                dualRunScreen(node, null)
                Timber.i("🖥️ SCREEN: UNKNOWN")
                return ScreenInfo.Simple(Screen.UNKNOWN)
            }

        dualRunScreen(node, screen)

        Timber.i("🖥️ SCREEN: ${screen.name}")
        return parserMap[screen]?.parse(node) ?: ScreenInfo.Simple(screen)
    }

    /**
     * Debug-only: run the JSON interpreter in parallel and log any disagreement.
     *
     * NOTE: DELIVERY_SUMMARY_EXPANDED vs DELIVERY_SUMMARY_COLLAPSED disagreements are
     * expected — the Kotlin matcher always returns EXPANDED and lets the parser correct
     * it to COLLAPSED, whereas the JSON rules detect this at the matching step. These
     * disagreements will resolve when parsers are migrated to Phase A3.
     *
     * The JSON interpreter is NEVER authoritative in this phase; logs only.
     */
    private fun dualRunScreen(node: UiNode, kotlinResult: Screen?) {
        if (!BuildConfig.DEBUG) return
        val ruleset = interpreter.screenRuleset ?: return

        val jsonResult = ruleset.matchFirst(node)
        if (jsonResult != kotlinResult) {
            Timber.w(
                "MATCHER_DISAGREE [screen] kotlin=${kotlinResult?.name ?: "UNKNOWN"} " +
                    "json=${jsonResult?.name ?: "UNKNOWN"}"
            )
        }
    }
}
