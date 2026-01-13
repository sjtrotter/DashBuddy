package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.statev2.AppEffect
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.Reducer
import cloud.trotter.dashbuddy.util.UtilityFunctions

object SummaryReducer {

    // --- FACTORY ---
    fun transitionTo(
        oldState: AppStateV2,
        input: ScreenInfo.DashSummary,
        isRecovery: Boolean
    ): Reducer.Transition {
        val newState = AppStateV2.PostDash(
            dashId = oldState.dashId,
            totalEarnings = input.totalEarnings ?: 0.0,
            durationMillis = input.onlineDurationMillis,
            acceptanceRateForSession = "${input.offersAccepted}/${input.offersTotal}"
        )

        val effects = mutableListOf<AppEffect>()

        // LOGIC: Dash Stop event.
        // We log the full summary data so we can reconcile it with our internal tracking later.
        val event = ReducerUtils.createEvent(
            dashId = oldState.dashId,
            type = AppEventType.DASH_STOP,
            payload = input // Serializes the summary stats
        )

        effects.add(AppEffect.LogEvent(event))
        effects.add(AppEffect.StopOdometer)

        val earningStr =
            input.totalEarnings?.let { UtilityFunctions.formatCurrency(it) } ?: "Unknown"
        effects.add(AppEffect.UpdateBubble("Dash Ended. Total: $earningStr"))
        effects.add(AppEffect.CaptureScreenshot("DashSummary - ${input.totalEarnings}"))

        return Reducer.Transition(newState, effects)
    }

    // --- REDUCER ---
    fun reduce(
        state: AppStateV2.PostDash,
        input: ScreenInfo
    ): Reducer.Transition? {
        return when (input) {
            is ScreenInfo.IdleMap -> {
                // User closed the summary -> Fully Offline
                IdleReducer.transitionTo(state, input, isRecovery = false)
            }

            is ScreenInfo.DashSummary -> {
                // Still looking at summary
                Reducer.Transition(state)
            }

            else -> {
                // Unknown input? Main reducer will handle it (likely transition to Idle/Unknown)
                null
            }
        }
    }
}