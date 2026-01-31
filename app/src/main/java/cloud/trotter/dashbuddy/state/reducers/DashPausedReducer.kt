package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.pipeline.recognition.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.Reducer
import cloud.trotter.dashbuddy.state.model.TimeoutType
import cloud.trotter.dashbuddy.state.reducers.postdelivery.PostDeliveryReducer
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class DashPausedReducer @Inject constructor(
    private val idleReducer: IdleReducer,
    private val awaitingReducerProvider: Provider<AwaitingReducer>,
    private val summaryReducer: SummaryReducer,
    private val postDeliveryReducerProvider: Provider<PostDeliveryReducer>,
) {

    fun onTimeout(
        state: AppStateV2,
        type: TimeoutType,
    ): Reducer.Transition {
        return when (type) {
            TimeoutType.DASH_PAUSED_SAFETY ->
                // Force transition to Idle (Dash Ended)
                idleReducer.transitionTo(
                    oldState = state,
                    // Create dummy info since we aren't looking at a screen
                    input = ScreenInfo.IdleMap(
                        screen = Screen.MAIN_MAP_IDLE,
                        zoneName = "Unknown",
                        dashType = null
                    ),
                    isRecovery = false
                ).copy(
                    effects = listOf(AppEffect.UpdateBubble("Dash Ended (Timeout)"))
                )

            else -> Reducer.Transition(state) // ignore other types
        }
    }

    fun transitionTo(
        oldState: AppStateV2,
        input: ScreenInfo.DashPaused,
        isRecovery: Boolean
    ): Reducer.Transition {

        val durationMs = input.remainingMillis + 1000 // +1s buffer

        val newState = AppStateV2.DashPaused(
            dashId = oldState.dashId,
            durationMs = durationMs
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
            effects.add(
                AppEffect.ScheduleTimeout(
                    durationMs = durationMs,
                    type = TimeoutType.DASH_PAUSED_SAFETY
                )
            )

            effects.add(AppEffect.UpdateBubble("Dash Paused!"))
        }

        return Reducer.Transition(newState, effects)
    }

    fun reduce(state: AppStateV2.DashPaused, input: ScreenInfo): Reducer.Transition? {
        return when (input) {
            is ScreenInfo.DashPaused -> {
                // Update timer estimate if it drifted
                val newEnd = System.currentTimeMillis() + input.remainingMillis
                Reducer.Transition(state.copy(durationMs = newEnd))
            }

            // IF WE SEE ANY OTHER SCREEN, WE ARE NOT PAUSED ANYMORE.
            // WE MUST CANCEL THE TIMER.

            is ScreenInfo.WaitingForOffer -> {
                // Resumed -> Cancel Timer -> Go to Awaiting
                val t = awaitingReducerProvider.get().transitionTo(state, input, isRecovery = false)
                t.copy(effects = t.effects + AppEffect.CancelTimeout(TimeoutType.DASH_PAUSED_SAFETY))
            }

            is ScreenInfo.DashSummary -> {
                // Ended Manually -> Cancel Timer -> Go to Summary
                val t = summaryReducer.transitionTo(state, input, isRecovery = false)
                t.copy(effects = t.effects + AppEffect.CancelTimeout(TimeoutType.DASH_PAUSED_SAFETY))
            }

            is ScreenInfo.IdleMap -> {
                // Ended Manually -> Cancel Timer -> Go to Idle
                val t = idleReducer.transitionTo(state, input, isRecovery = false)
                t.copy(effects = t.effects + AppEffect.CancelTimeout(TimeoutType.DASH_PAUSED_SAFETY))
            }

            is ScreenInfo.DeliveryCompleted -> postDeliveryReducerProvider.get().transitionTo(
                state,
                input,
                isRecovery = false
            )

            else -> null
        }
    }
}