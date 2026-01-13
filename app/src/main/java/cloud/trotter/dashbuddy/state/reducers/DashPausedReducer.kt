package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.Reducer

object DashPausedReducer {

    fun transitionTo(
        oldState: AppStateV2,
        input: ScreenInfo.DashPaused,
        isRecovery: Boolean
    ): Reducer.Transition {

        // Calculate the exact time the dash will end
        val endTime = System.currentTimeMillis() + input.remainingMillis + 1000 // +1s buffer

        val newState = AppStateV2.DashPaused(
            dashId = oldState.dashId,
            expectedEndAt = endTime
        )

        val effects = mutableListOf<AppEffect>()

        if (!isRecovery) {
            // 1. Log it
            effects.add(
                AppEffect.LogEvent(
                    ReducerUtils.createEvent(
                        oldState.dashId,
                        AppEventType.DASH_PAUSED, // Ensure this exists in Enum
                        "Paused. Time left: ${input.rawTimeText}"
                    )
                )
            )

            // 2. Schedule the "Force End" side effect
            // We tag it "DASH_PAUSE_TIMER" so we can cancel this specific timer later
            effects.add(AppEffect.ScheduleTimeout(endTime, "DASH_PAUSE_TIMER"))

            effects.add(AppEffect.UpdateBubble("Paused: ${input.rawTimeText}"))
        }

        return Reducer.Transition(newState, effects)
    }

    fun reduce(state: AppStateV2.DashPaused, input: ScreenInfo): Reducer.Transition? {
        return when (input) {
            is ScreenInfo.DashPaused -> {
                // Update timer estimate if it drifted
                val newEnd = System.currentTimeMillis() + input.remainingMillis
                Reducer.Transition(state.copy(expectedEndAt = newEnd))
            }

            // IF WE SEE ANY OTHER SCREEN, WE ARE NOT PAUSED ANYMORE.
            // WE MUST CANCEL THE TIMER.

            is ScreenInfo.WaitingForOffer -> {
                // Resumed -> Cancel Timer -> Go to Awaiting
                val t = AwaitingReducer.transitionTo(state, input, isRecovery = false)
                t.copy(effects = t.effects + AppEffect.CancelTimeout("DASH_PAUSE_TIMER"))
            }

            is ScreenInfo.DashSummary -> {
                // Ended Manually -> Cancel Timer -> Go to Summary
                val t = SummaryReducer.transitionTo(state, input, isRecovery = false)
                t.copy(effects = t.effects + AppEffect.CancelTimeout("DASH_PAUSE_TIMER"))
            }

            is ScreenInfo.IdleMap -> {
                // Ended Manually -> Cancel Timer -> Go to Idle
                val t = IdleReducer.transitionTo(state, input, isRecovery = false)
                t.copy(effects = t.effects + AppEffect.CancelTimeout("DASH_PAUSE_TIMER"))
            }

            is ScreenInfo.DeliveryCompleted -> PostDeliveryReducer.transitionTo(
                state,
                input,
                isRecovery = false
            )

            else -> null
        }
    }
}