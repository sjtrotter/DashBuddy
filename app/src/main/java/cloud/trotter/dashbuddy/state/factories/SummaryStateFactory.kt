package cloud.trotter.dashbuddy.state.factories

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.model.Transition
import cloud.trotter.dashbuddy.state.reducers.ReducerUtils
import cloud.trotter.dashbuddy.util.UtilityFunctions
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SummaryStateFactory @Inject constructor() {

    fun createEntry(
        oldState: AppStateV2,
        input: ScreenInfo.DashSummary,
        isRecovery: Boolean
    ): Transition {
        val newState = AppStateV2.PostDash(
            dashId = oldState.dashId,
            totalEarnings = input.totalEarnings ?: 0.0,
            durationMillis = input.onlineDurationMillis,
            acceptanceRateForSession = "${input.offersAccepted}/${input.offersTotal}"
        )

        val effects = mutableListOf<AppEffect>()

        // LOGIC: Dash Stop event
        val event = ReducerUtils.createEvent(
            dashId = oldState.dashId,
            type = AppEventType.DASH_STOP,
            payload = input
        )

        effects.add(AppEffect.LogEvent(event))
        effects.add(AppEffect.StopOdometer)

        val earningStr =
            input.totalEarnings?.let { UtilityFunctions.formatCurrency(it) } ?: "Unknown"
        effects.add(
            AppEffect.UpdateBubble(
                "Dash Ended. Total: $earningStr",
                ChatPersona.Dispatcher
            )
        )
        effects.add(AppEffect.CaptureScreenshot("DashSummary - ${input.totalEarnings}"))

        return Transition(newState, effects)
    }
}