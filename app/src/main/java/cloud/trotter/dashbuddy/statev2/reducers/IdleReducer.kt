package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.statev2.AppEffect
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.Reducer

object IdleReducer {

    // --- FACTORY (Entry Point) ---
    fun transitionTo(
        oldState: AppStateV2,
        input: ScreenInfo.IdleMap,
        isRecovery: Boolean
    ): Reducer.Transition {
        val newState = AppStateV2.IdleOffline(
            lastKnownZone = input.zoneName,
            dashType = input.dashType
        )

        val effects = mutableListOf<AppEffect>()

        // If we were previously in a Dash, this is a STOP event.
        if (oldState.dashId != null) {
            val stopEvent = ReducerUtils.createEvent(
                dashId = oldState.dashId!!,
                type = AppEventType.DASH_STOP,
                payload = "Return to Map"
            )
            effects.add(AppEffect.LogEvent(stopEvent))
            effects.add(AppEffect.UpdateBubble("Dash Ended"))
        }

        return Reducer.Transition(newState, effects)
    }

    // --- REDUCER (Behavior) ---
    fun reduce(
        state: AppStateV2.IdleOffline,
        input: ScreenInfo,
        context: StateContext
    ): Reducer.Transition? {
        return when (input) {
            is ScreenInfo.IdleMap -> {
                // Internal Update
                if (state.lastKnownZone != input.zoneName || state.dashType != input.dashType) {
                    Reducer.Transition(
                        state.copy(
                            lastKnownZone = input.zoneName,
                            dashType = input.dashType
                        )
                    )
                } else {
                    Reducer.Transition(state)
                }
            }

            is ScreenInfo.WaitingForOffer -> {
                // START DASH
                AwaitingReducer.transitionTo(state, input, isRecovery = false)
            }

            else -> null
        }
    }
}