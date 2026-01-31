package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.Reducer
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class IdleReducer @Inject constructor(
    private val bubbleManager: BubbleManager,
    private val awaitingReducerProvider: Provider<AwaitingReducer>
) {

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
            bubbleManager.endDash()
            effects.add(AppEffect.LogEvent(stopEvent))
            effects.add(AppEffect.StopOdometer)
            effects.add(AppEffect.UpdateBubble("Dash Ended", ChatPersona.Dispatcher))
        }

        return Reducer.Transition(newState, effects)
    }

    // --- REDUCER (Behavior) ---
    fun reduce(
        state: AppStateV2.IdleOffline,
        input: ScreenInfo
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
                awaitingReducerProvider.get().transitionTo(state, input, isRecovery = false)
            }

            else -> null
        }
    }
}