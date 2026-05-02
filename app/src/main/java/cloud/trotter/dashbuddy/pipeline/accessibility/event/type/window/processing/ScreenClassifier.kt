package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing

import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
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

    fun classify(node: UiNode): Observation.Screen {
        // --- Kotlin matchers: authoritative ---
        val screen = allMatchers.firstNotNullOfOrNull { it.matches(node) }
            ?: run {
                dualRunScreen(node, null)
                Timber.i("SCREEN: UNKNOWN")
                return makeObservation(Screen.UNKNOWN, ParsedFields.None)
            }

        dualRunScreen(node, screen)

        Timber.i("SCREEN: ${screen.name}")
        val parsed = parserMap[screen]?.parse(node) ?: ParsedFields.None
        return makeObservation(screen, parsed)
    }

    private fun makeObservation(screen: Screen, parsed: ParsedFields): Observation.Screen {
        val (flow, modeHint) = screenFlowMapping(screen)
        return Observation.Screen(
            timestamp = System.currentTimeMillis(),
            captureId = null,
            ruleId = "doordash.screen.${screen.name}",
            metadata = ReplayMetadata.EMPTY,
            flow = flow,
            modeHint = modeHint,
            parsed = parsed,
            target = screen.name,
        )
    }

    /**
     * Debug-only: run the JSON interpreter in parallel and log any disagreement.
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

    companion object {
        /**
         * Maps Screen enum → (Flow?, Mode?) for the Observation IR.
         * Screens not listed here contribute no flow/mode signal.
         */
        fun screenFlowMapping(screen: Screen): Pair<Flow?, Mode?> = when (screen) {
            // Idle / Waiting
            Screen.MAIN_MAP_IDLE -> Flow.Idle to Mode.Offline
            Screen.ON_DASH_MAP_WAITING_FOR_OFFER -> Flow.Idle to Mode.Online
            Screen.ON_DASH_ALONG_THE_WAY -> Flow.Idle to Mode.Online
            Screen.SET_DASH_END_TIME -> Flow.Idle to null

            // Offer
            Screen.OFFER_POPUP -> Flow.OfferPresented to null
            Screen.OFFER_POPUP_CONFIRM_DECLINE -> Flow.OfferPresented to null

            // Pickup
            Screen.NAVIGATION_VIEW_TO_PICK_UP -> Flow.TaskPickupNavigation to Mode.Online
            Screen.PICKUP_DETAILS_PRE_ARRIVAL -> Flow.TaskPickupNavigation to Mode.Online
            Screen.PICKUP_DETAILS_PRE_ARRIVAL_PICKUP_MULTI -> Flow.TaskPickupNavigation to Mode.Online
            Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE -> Flow.TaskPickupArrived to Mode.Online
            Screen.PICKUP_DETAILS_VERIFY_PICKUP -> Flow.TaskPickupArrived to Mode.Online
            Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_MULTI -> Flow.TaskPickupArrived to Mode.Online
            Screen.PICKUP_DETAILS_POST_ARRIVAL_SHOP -> Flow.TaskPickupArrived to Mode.Online
            Screen.PICKUP_DETAILS_PICKED_UP -> Flow.TaskPickupArrived to Mode.Online

            // Dropoff
            Screen.NAVIGATION_VIEW_TO_DROP_OFF -> Flow.TaskDropoffNavigation to Mode.Online
            Screen.DROPOFF_DETAILS_PRE_ARRIVAL -> Flow.TaskDropoffArrived to Mode.Online

            // Post-delivery
            Screen.DELIVERY_SUMMARY_COLLAPSED -> Flow.PostTask to Mode.Online
            Screen.DELIVERY_SUMMARY_EXPANDED -> Flow.PostTask to Mode.Online

            // Session end
            Screen.DASH_SUMMARY_SCREEN -> Flow.SessionEnded to Mode.Offline

            // Paused
            Screen.DASH_PAUSED -> null to Mode.Paused

            // Informational (no flow/mode contribution)
            Screen.TIMELINE_VIEW -> null to null
            Screen.RATINGS_VIEW -> null to null
            Screen.EARNINGS_VIEW -> null to null
            Screen.SCHEDULE_VIEW -> null to null
            Screen.CHAT_VIEW -> null to null
            Screen.NOTIFICATIONS_VIEW -> null to null
            Screen.PROMOS_VIEW -> null to null
            Screen.HELP_VIEW -> null to null
            Screen.SAFETY_VIEW -> null to null
            Screen.NAVIGATION_VIEW -> null to null
            Screen.MAIN_MAP_ON_DASH -> null to Mode.Online
            Screen.APP_STARTING_OR_LOADING -> null to null
            Screen.SENSITIVE -> null to null
            Screen.UNKNOWN -> null to null
        }
    }
}
